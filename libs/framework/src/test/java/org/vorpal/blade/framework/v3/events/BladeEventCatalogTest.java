package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The framework's own event types, declared in the catalog so it describes the
/// whole bus rather than only what applications add.
///
/// These are checked the same way an application's types are — they generate,
/// they validate, they produce a usable selector — because the point of putting
/// them in the catalog is that nothing about them is special.
class BladeEventCatalogTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	@DisplayName("every framework type has a name, a class name and fields")
	void allTypesAreComplete() {
		List<EventType> types = BladeEventCatalog.analyticsTypes();
		assertFalse(types.isEmpty());
		for (EventType type : types) {
			assertNotNull(type.getType(), "missing type name");
			assertNotNull(type.getTitle(), type.getType() + " has no title");
			assertNotNull(type.getDescription(), type.getType() + " has no description");
			assertNotNull(type.effectiveJavaClassName(), type.getType() + " has no class name");
			assertFalse(type.fieldsOrEmpty().isEmpty(), type.getType() + " declares no fields");
			assertNotNull(type.getVersion(),
					type.getType() + " has no version — the producer could not stamp dataversion");
		}
	}

	/// Every type the catalog declares, from both of its lists.
	///
	/// The catalog has two on purpose: [BladeEventCatalog#analyticsTypes] is what
	/// the analytics database is built from, and [BladeEventCatalog#accessTypes]
	/// is the access-audit pair, which must stay off that subscription because it
	/// answers to a different reader under a different retention. The drift guard
	/// below is about *declaration*, not about routing, so it looks at both.
	private static Set<String> declaredTypes() {
		Set<String> declared = new HashSet<>();
		for (EventType type : BladeEventCatalog.analyticsTypes()) {
			declared.add(type.getType());
		}
		for (EventType type : BladeEventCatalog.accessTypes()) {
			declared.add(type.getType());
		}
		return declared;
	}

	/// Every `public static final String` on [BladeEventTypes] must have a
	/// declaration in [BladeEventCatalog].
	///
	/// **This is the drift guard, and it replaces a `default:` branch that used to
	/// invent a description for anything it did not recognise.** The two files are
	/// a closed set stated twice; nothing but this test ties them together. A
	/// constant added without a declaration would publish onto a type the catalog
	/// does not know — and with `rejectUnknownTypes` on, that is a failed publish
	/// on the SIP container thread. Reflection rather than a hand-written list, so
	/// the test cannot itself go stale.
	@Test
	@DisplayName("every stamped type constant has a declaration")
	void coversEveryStampedType() throws Exception {
		Set<String> declared = declaredTypes();
		int constants = 0;

		for (java.lang.reflect.Field field : BladeEventTypes.class.getDeclaredFields()) {
			int modifiers = field.getModifiers();
			if (!java.lang.reflect.Modifier.isPublic(modifiers) || !java.lang.reflect.Modifier.isStatic(modifiers)
					|| field.getType() != String.class) {
				continue;
			}
			constants++;
			String type = (String) field.get(null);
			assertTrue(declared.contains(type),
					"BladeEventTypes." + field.getName() + " (" + type + ") has no declaration in BladeEventCatalog");
		}

		assertEquals(constants, declared.size(),
				"BladeEventCatalog declares a type that BladeEventTypes does not name");
		assertEquals(19, constants, "a type was added or removed without updating the taxonomy");
	}

	/// Every framework event name resolves to a type the catalog declares.
	///
	/// The companion to the guard above, from the other end: `forEventName` is
	/// what the producer calls, so a name it maps to an undeclared type would be
	/// a publish the ingress rejects.
	@Test
	@DisplayName("every framework event name maps to a declared type")
	void everyEventNameMapsToADeclaredType() {
		Set<String> declared = declaredTypes();
		String[] names = { "callStarted", "callAnswered", "callConnected", "callCompleted", "callAbandoned",
				"callDeclined", "transferRequested", "transferInitiated", "transferCompleted", "transferDeclined",
				"transferAbandoned" };

		Set<String> mapped = new HashSet<>();
		for (String name : names) {
			String type = BladeEventTypes.forEventName(name);
			assertNotEquals(BladeEventTypes.CALL_EVENT, type,
					name + " is a framework name and must have a type of its own, not the operator fallback");
			assertTrue(declared.contains(type), name + " maps to " + type + ", which the catalog does not declare");
			mapped.add(type);
		}
		assertEquals(names.length, mapped.size(), "two event names collapsed onto one type");
	}

	@Test
	@DisplayName("a name the framework does not define falls back to the operator type")
	void operatorDefinedNamesFallBack() {
		assertEquals(BladeEventTypes.CALL_EVENT, BladeEventTypes.forEventName("agentWrapUp"));
		assertEquals(BladeEventTypes.CALL_EVENT, BladeEventTypes.forEventName(null));
	}

	/// `versionOf` is what the framework's own producers stamp as `dataversion`,
	/// so it must agree with the declarations — and stay null for a type the
	/// catalog does not declare, rather than inventing a revision.
	@Test
	@DisplayName("versionOf agrees with the declarations and is null for unknown types")
	void versionLookupMatchesDeclarations() {
		for (EventType type : BladeEventCatalog.analyticsTypes()) {
			assertEquals(type.getVersion(), BladeEventCatalog.versionOf(type.getType()), type.getType());
		}
		assertNull(BladeEventCatalog.versionOf("net.example.not.declared"));
	}

	/// The eleven descriptions are contract prose — they become the schema
	/// `description` every consumer reads — so they must be written per type, not
	/// derived from the event name.
	@Test
	@DisplayName("no two types share a description")
	void descriptionsAreWrittenNotDerived() {
		Set<String> seen = new HashSet<>();
		for (EventType type : BladeEventCatalog.analyticsTypes()) {
			assertTrue(seen.add(type.getDescription()),
					type.getType() + " reuses another type's description: prose is stated once per type, "
							+ "by someone who read the emit site");
		}
	}

	@Test
	@DisplayName("call-scoped types carry the correlator; application lifecycle does not")
	void correlatorIsOnTheRightTypes() {
		for (EventType type : BladeEventCatalog.analyticsTypes()) {
			boolean hasVorpalId = false;
			boolean hasStartedAt = false;
			for (EventField field : type.fieldsOrEmpty()) {
				hasVorpalId |= "vorpalId".equals(field.getName());
				hasStartedAt |= "startedAt".equals(field.getName());
			}
			boolean applicationScoped = type.getType().contains(".application.");
			assertEquals(!applicationScoped, hasVorpalId, type.getType() + " correlator presence");
			assertEquals(!applicationScoped, hasStartedAt,
					type.getType() + " must carry the call birth instant, not just the id");
		}
	}

	@Test
	@DisplayName("every framework type generates a schema and a compilable class")
	void allTypesGenerate() {
		for (EventType type : BladeEventCatalog.analyticsTypes()) {
			JsonNode schema = EventSourceGenerator.schema(type, MAPPER);
			assertEquals("object", schema.path("type").asText(), type.getType());
			assertTrue(schema.path("properties").size() > 0, type.getType() + " produced no properties");

			String java = EventSourceGenerator.javaSource(type);
			assertTrue(java.contains("public class " + type.effectiveJavaClassName()), type.getType());
			assertFalse(java.contains("/**"), type.getType() + " emitted legacy Javadoc");
		}
	}

	/// The framework's own types generate a working consumer, one type at a time —
	/// the shape an *actor* takes, as opposed to the analytics sink.
	@Test
	@DisplayName("each framework type generates a consumer whose selector names it")
	void eachTypeIsConsumable() {
		EventCatalog catalog = new EventCatalog();
		catalog.setTypes(BladeEventCatalog.analyticsTypes());

		for (EventType type : catalog.typesOrEmpty()) {
			EventSubscription subscription = new EventSubscription("watch-" + type.effectiveJavaClassName());
			subscription.setTypes(java.util.Collections.singletonList(type.getType()));
			subscription.setJavaPackage("com.example.consumer");

			String mdb = EventSourceGenerator.consumerSource(subscription, catalog);
			// The selector is derived at runtime from this list, so the list is
			// what the generated source has to get right.
			assertTrue(mdb.contains("\"" + type.getType() + "\""),
					type.getType() + " is not in the generated consumer's type list");
			assertTrue(mdb.contains("SubscriptionRegistrar.start(event.getServletContext(), SUBSCRIPTION,"),
					type.getType() + " consumer does not start its own subscription");
			assertTrue(mdb.contains("on" + type.effectiveJavaClassName() + "(CloudEvent event"),
					type.getType() + " has no handler stub");
		}
	}

	/// The sink subscription is the other shape: no selector, everything, and a
	/// class name and identity that cannot collide with an actor's.
	@Test
	@DisplayName("the analytics sink takes everything and owns its own identity")
	void theSinkTakesEverything() {
		EventCatalog catalog = new EventCatalog();
		catalog.setTypes(BladeEventCatalog.analyticsTypes());

		EventSubscription sink = BladeEventCatalog.analyticsSubscription();
		catalog.setSubscriptions(java.util.Collections.singletonList(sink));

		// The sink still declares SelectorMode.NONE, which is now a statement
		// about generation rather than about runtime: it names no types, so a
		// generated file cannot derive a selector for it. The deployed sink
		// derives one from the catalog's persist flags every few seconds
		// instead — see AnalyticsSubscription — which is what removed the
		// "takes everything and discards most of it" cost.
		assertNull(sink.selector(), "a generated selector would freeze the persisted set");
		assertEquals(BladeEventCatalog.ANALYTICS_SUBSCRIPTION, sink.clientId());
		assertEquals(BladeEventCatalog.ANALYTICS_SUBSCRIPTION, sink.subscriptionName());
		assertTrue(catalog.validate().isEmpty(), catalog.validate().toString());

		String mdb = EventSourceGenerator.consumerSource(sink, catalog);
		assertFalse(mdb.contains("messageSelector"));
		assertTrue(mdb.contains("public class AnalyticsEventListener"), mdb);
		assertTrue(mdb.contains("implements EventSubscriber.Handler, ServletContextListener"), mdb);
	}

	@Test
	@DisplayName("every framework type is flagged for the database")
	void everyTypeIsPersisted() {
		for (EventType type : BladeEventCatalog.analyticsTypes()) {
			assertTrue(type.isPersist(), type.getType() + " is what the analytics database is built from");
		}
	}

	@Test
	@DisplayName("the call-event attribute array is declarable, which a free-form map would not be")
	void callEventAttributesAreDeclared() {
		EventType callEvent = null;
		for (EventType type : BladeEventCatalog.analyticsTypes()) {
			if (BladeEventTypes.CALL_EVENT.equals(type.getType())) {
				callEvent = type;
			}
		}
		assertNotNull(callEvent);

		JsonNode attributes = EventSourceGenerator.schema(callEvent, MAPPER).path("properties").path("attributes");
		assertEquals("array", attributes.path("type").asText());
		assertEquals("object", attributes.path("items").path("type").asText());
		assertTrue(attributes.path("items").path("properties").has("name"));
		assertTrue(attributes.path("items").path("properties").has("value"));
	}

	@Test
	@DisplayName("a sessionless call event is legal — the correlator is optional there")
	void callEventCorrelatorIsOptional() {
		for (EventType type : BladeEventCatalog.analyticsTypes()) {
			if (!BladeEventTypes.CALL_EVENT.equals(type.getType())) {
				continue;
			}
			for (EventField field : type.fieldsOrEmpty()) {
				if ("vorpalId".equals(field.getName()) || "startedAt".equals(field.getName())) {
					assertFalse(field.isRequired(), field.getName() + " must be optional on a sessionless event");
				}
			}
		}
	}
}
