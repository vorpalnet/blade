package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Covers [EventSourceGenerator] and the event catalog model.
///
/// The load-bearing test is
/// [SchemaDialect#bothProducersAgreeOnTheSamePayload]: it pins the designer's
/// hand-built schema against what [SettingsManager#generateSchemaNode] produces
/// for an equivalent Java class. There are now two schema producers in this
/// codebase, and nothing else would notice if they drifted into different
/// dialects — the symptom would be a payload schema that quietly stops
/// rendering in the console.
class EventSourceGeneratorTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// The declaration everything is exercised against — deliberately covers a
	/// snake_case wire name, required fields, an enum, a nested object and an
	/// array.
	private static EventType fixture() {
		EventType declaration = new EventType("net.vorpal.attendant.meeting.scheduled");
		declaration.setTitle("Meeting Scheduled");
		declaration.setDescription("Emitted when the attendant has confirmed a meeting with the caller.");
		declaration.setJavaPackage("com.example.events");
		declaration.setDestinationJndi("jms/BladeEventBusTopic");
		declaration.setDestinationKind(DestinationKind.TOPIC);

		EventField who = new EventField("who", EventFieldType.STRING, true);
		who.setDescription("Who the meeting is with.");

		EventField whenText = new EventField("when_text", EventFieldType.STRING, true);
		whenText.setDescription("When, as the caller said it.");

		EventField attendeeCount = new EventField("attendee_count", EventFieldType.INTEGER, false);
		attendeeCount.setDescription("How many people are invited.");
		attendeeCount.setDefaultValue("2");

		EventField status = new EventField("status", EventFieldType.ENUM, false);
		status.setEnumValues(Arrays.asList("confirmed", "tentative", "needs-follow-up"));

		EventField room = new EventField("room", EventFieldType.STRING, false);
		EventField floor = new EventField("floor", EventFieldType.INTEGER, false);
		EventField location = new EventField("location", EventFieldType.OBJECT, false);
		location.setFields(Arrays.asList(room, floor));

		EventField attendees = new EventField("attendees", EventFieldType.ARRAY, false);
		attendees.setItemType(EventFieldType.STRING);

		declaration.setFields(Arrays.asList(who, whenText, attendeeCount, status, location, attendees));
		return declaration;
	}

	@Nested
	@DisplayName("wire names map to Java names")
	class Naming {

		@Test
		void snakeCaseBecomesCamelCaseAndKeepsItsWireBinding() {
			EventField snake = new EventField("when_text", EventFieldType.STRING, false);
			assertEquals("whenText", snake.javaName());
			assertEquals("WhenText", snake.javaTypeName());
			assertTrue(snake.needsJsonPropertyBinding(),
					"a Java name differing from the wire name needs @JsonProperty");
		}

		@Test
		void hyphensAreSeparatorsToo() {
			assertEquals("needsFollowUp", new EventField("needs-follow-up", EventFieldType.STRING, false).javaName());
		}

		@Test
		void anAlreadyCamelCaseNameNeedsNoBinding() {
			EventField already = new EventField("who", EventFieldType.STRING, false);
			assertEquals("who", already.javaName());
			assertFalse(already.needsJsonPropertyBinding());
		}

		@Test
		@DisplayName("a half-authored field does not throw — the designer previews on every keystroke")
		void anUnnamedFieldReturnsNull() {
			assertNull(new EventField().javaName());
		}
	}

	@Nested
	@DisplayName("the selector is derived, never typed")
	class Selector {

		@Test
		void selectorComesFromTheEventType() {
			assertEquals("eventType = 'net.vorpal.attendant.meeting.scheduled'", fixture().selector());
		}

		@Test
		@DisplayName("selector uses the same property the publisher stamps")
		void selectorUsesThePublishersProperty() {
			assertTrue(fixture().selector().startsWith(EventPublisher.PROP_TYPE + " "));
		}

		@Test
		void aTypelessDeclarationHasNoSelector() {
			assertNull(new EventType().selector());
		}

		@Test
		void theClassNameDerivesFromTheType() {
			EventType derived = new EventType("net.vorpal.attendant.meeting.scheduled");
			assertEquals("Scheduled", derived.effectiveJavaClassName());
		}

		@Test
		@DisplayName("durability is meaningless on a queue and is ignored there")
		void durabilityIsIgnoredOnAQueue() {
			EventType queued = new EventType("a.b.c");
			queued.setDestinationKind(DestinationKind.QUEUE);

			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(Arrays.asList(queued));

			EventSubscription subscription = new EventSubscription("worker");
			subscription.setTypes(Arrays.asList("a.b.c"));
			subscription.setDurable(true);

			assertFalse(catalog.isDurableTopic(subscription),
					"a queue already retains the message until someone consumes it");
		}
	}

	@Nested
	@DisplayName("declared versions travel")
	class Versions {

		private EventType versioned() {
			EventType declaration = fixture();
			declaration.setVersion(2);
			return declaration;
		}

		@Test
		@DisplayName("the payload class records the revision it was generated from")
		void payloadCarriesVersionConstant() {
			assertTrue(EventSourceGenerator.javaSource(versioned()).contains("public static final int VERSION = 2;"));
			assertFalse(EventSourceGenerator.javaSource(fixture()).contains("VERSION"),
					"an unversioned declaration must not invent a revision");
		}

		@Test
		@DisplayName("the producer snippet stamps the payload's revision as dataversion")
		void producerStampsVersion() {
			assertTrue(EventSourceGenerator.producerSnippet(versioned()).contains("Scheduled.VERSION"));
			assertFalse(EventSourceGenerator.producerSnippet(fixture()).contains("VERSION"));
		}

		@Test
		@DisplayName("the consumer warns on skew — and emits no guard when nothing is versioned")
		void consumerChecksVersion() {
			EventSubscription subscription = new EventSubscription("calendar");
			subscription.setTypes(java.util.Collections.singletonList(fixture().getType()));
			subscription.setJavaPackage("com.example.consumer");

			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(java.util.Collections.singletonList(versioned()));
			String mdb = EventSourceGenerator.consumerSource(subscription, catalog);
			assertTrue(mdb.contains("checkVersion(event, 2);"));
			assertTrue(mdb.contains("regenerate this consumer"));

			EventCatalog unversioned = new EventCatalog();
			unversioned.setTypes(java.util.Collections.singletonList(fixture()));
			String plain = EventSourceGenerator.consumerSource(subscription, unversioned);
			assertFalse(plain.contains("checkVersion"), "no declared version, no guard");
		}
	}

	@Nested
	@DisplayName("generated payload class")
	class PayloadClass {

		private final String java = EventSourceGenerator.javaSource(fixture());

		@Test
		void declaresPackageAndClass() {
			assertTrue(java.contains("package com.example.events;"));
			assertTrue(java.contains("public class Scheduled implements Serializable"));
		}

		@Test
		@DisplayName("preserves the wire name rather than reshaping it for Java")
		void preservesTheWireName() {
			assertTrue(java.contains("@JsonProperty(\"when_text\")"));
			assertTrue(java.contains("public String getWhenText()"));
		}

		@Test
		@DisplayName("puts @JsonPropertyDescription on the getter, not the field")
		void descriptionGoesOnTheGetter() {
			int annotation = java.indexOf("@JsonPropertyDescription(\"Who the meeting is with.\")");
			int getter = java.indexOf("public String getWho()");
			assertTrue(annotation > 0 && annotation < getter);
			assertFalse(java.contains("@JsonPropertyDescription(\"Who the meeting is with.\")\n\tprivate"));
		}

		@Test
		void enumFieldBecomesANestedEnumWithLegalConstants() {
			assertTrue(java.contains("public enum Status {"));
			assertTrue(java.contains("NEEDS_FOLLOW_UP"), "non-identifier characters become underscores");
			assertTrue(java.contains("@JsonProperty(\"needs-follow-up\")"), "the wire value is preserved");
		}

		@Test
		void objectAndArrayFieldsBecomeNestedClassesAndLists() {
			assertTrue(java.contains("public static class Location implements Serializable {"));
			assertTrue(java.contains("private List<String> attendees;"));
			assertTrue(java.contains("import java.util.List;"));
		}

		@Test
		@DisplayName("emits only the imports it uses")
		void emitsNoUnusedImports() {
			assertFalse(java.contains("import java.time.Instant;"), "no field needs Instant");
		}

		@Test
		@DisplayName("uses /// Javadoc, never legacy /** */")
		void usesMarkdownJavadoc() {
			assertFalse(java.contains("/**"));
		}
	}

	/// A catalog holding the fixture type, for the subscription tests.
	private static EventCatalog catalog() {
		EventCatalog catalog = new EventCatalog();
		catalog.setTypes(new ArrayList<>(Arrays.asList(fixture())));
		return catalog;
	}

	private static EventSubscription subscription(String name, String... types) {
		EventSubscription subscription = new EventSubscription(name);
		subscription.setJavaPackage("com.example.consumer");
		if (types.length > 0) {
			subscription.setTypes(Arrays.asList(types));
		}
		return subscription;
	}

	@Nested
	@DisplayName("generated consumer")
	class Consumer {

		private final EventCatalog catalog = catalog();
		private final EventSubscription subscription = subscription("attendant-meetings",
				"net.vorpal.attendant.meeting.scheduled");
		private final String mdb = EventSourceGenerator.consumerSource(subscription, catalog);

		@Test
		void isAMessageListenerBoundToTheTypesDestination() {
			assertTrue(mdb.contains("public class AttendantMeetingsListener implements MessageListener"));
			assertTrue(mdb.contains("@MessageDriven(mappedName = \"jms/BladeEventBusTopic\""));
		}

		@Test
		@DisplayName("carries the derived selector — the drift this whole design exists to prevent")
		void carriesTheDerivedSelector() {
			assertTrue(mdb.contains("propertyValue = \"" + subscription.selector() + "\""),
					"the selector must be the one the subscription derives, not a hand-typed copy");
			assertTrue(subscription.selector().contains("net.vorpal.attendant.meeting.scheduled"));
		}

		@Test
		void aDurableTopicSubscribesDurablyAndOncePerCluster() {
			assertTrue(mdb.contains("propertyValue = \"javax.jms.Topic\""));
			assertTrue(mdb.contains("propertyValue = \"Durable\""));
			assertTrue(mdb.contains("One-Copy-Per-Application"));
		}

		/// Omitting this is not a compile error and not a deployment error — the
		/// container quietly falls back to a default connection factory. The
		/// factory the bus provisions would then apply to nothing, and its
		/// prefetch settings would silently not be in effect.
		@Test
		@DisplayName("names the connection factory rather than letting the container pick one")
		void namesTheConnectionFactory() {
			assertTrue(mdb.contains("propertyName = \"connectionFactoryJndiName\", propertyValue = \""
					+ catalog.getConnectionFactoryJndi() + "\""), mdb);
		}

		@Test
		@DisplayName("subscription identity comes from the subscription, never from the event type")
		void identityComesFromTheSubscription() {
			assertTrue(mdb.contains("propertyName = \"subscriptionName\", propertyValue = \"attendant-meetings\""));
			assertTrue(mdb.contains("propertyName = \"clientId\", propertyValue = \"attendant-meetings\""));
			assertFalse(mdb.contains("ScheduledSub"),
					"deriving the identity from the event type is exactly the defect this replaced");
		}

		@Test
		void parsesTheEnvelopeThenTheTypedPayload() {
			assertTrue(mdb.contains("CloudEvent.fromJson"));
			assertTrue(mdb.contains("MAPPER.treeToValue(event.getData(), Scheduled.class)"));
		}

		@Test
		@DisplayName("dedupes on the event id in code, not in a comment")
		void dedupesOnTheEventId() {
			assertTrue(mdb.contains("private static boolean firstSight(String id)"));
			assertTrue(mdb.contains("if (!firstSight(event.getId()))"),
					"a durable subscriber sees redeliveries; a note nobody acts on is not a guard");
		}

		@Test
		@DisplayName("imports a payload class that lives in another package")
		void importsThePayloadClass() {
			assertTrue(mdb.contains("import com.example.events.Scheduled;"));
		}

		@Test
		@DisplayName("a queue consumer declares no durable subscription")
		void queueConsumerHasNoSubscription() {
			EventType queued = fixture();
			queued.setDestinationKind(DestinationKind.QUEUE);
			EventCatalog queueCatalog = new EventCatalog();
			queueCatalog.setTypes(Arrays.asList(queued));

			String queueMdb = EventSourceGenerator.consumerSource(subscription, queueCatalog);
			assertTrue(queueMdb.contains("propertyValue = \"javax.jms.Queue\""));
			assertFalse(queueMdb.contains("subscriptionDurability"));
		}

		@Test
		void usesMarkdownJavadoc() {
			assertFalse(mdb.contains("/**"));
		}
	}

	/// The regression suite for the defect that made subscriptions first-class.
	///
	/// Deriving the JMS identity from the *event type* meant two applications
	/// consuming one event generated the same `subscriptionName` and the same
	/// `clientId` — not two subscriptions clashing, but one subscription named
	/// twice, so the two apps competed for a single stream instead of each
	/// receiving a copy. Nothing downstream would have reported that: both apps
	/// would simply have received roughly half their events, intermittently.
	@Nested
	@DisplayName("two apps, one event")
	class MultipleSubscribers {

		private final EventCatalog catalog = catalog();
		private final String type = "net.vorpal.attendant.meeting.scheduled";

		@Test
		@DisplayName("two subscriptions on the same type get distinct JMS identities")
		void distinctIdentities() {
			String actor = EventSourceGenerator.consumerSource(subscription("transfer", type), catalog);
			String sink = EventSourceGenerator.consumerSource(subscription("analytics-db", type), catalog);

			assertTrue(actor.contains("propertyName = \"clientId\", propertyValue = \"transfer\""));
			assertTrue(sink.contains("propertyName = \"clientId\", propertyValue = \"analytics-db\""));
			assertTrue(actor.contains("propertyName = \"subscriptionName\", propertyValue = \"transfer\""));
			assertTrue(sink.contains("propertyName = \"subscriptionName\", propertyValue = \"analytics-db\""));
		}

		@Test
		@DisplayName("and distinct MDB class names, so one app's backpressure cannot suspend the other")
		void distinctClassNames() {
			String actor = EventSourceGenerator.consumerSource(subscription("transfer", type), catalog);
			String sink = EventSourceGenerator.consumerSource(subscription("analytics-db", type), catalog);

			assertTrue(actor.contains("public class TransferListener implements MessageListener"));
			assertTrue(sink.contains("public class AnalyticsDbListener implements MessageListener"));
		}

		@Test
		@DisplayName("a duplicate subscription name is a catalog finding, not a silent merge")
		void duplicateNamesAreReported() {
			catalog.setSubscriptions(Arrays.asList(subscription("transfer", type), subscription("transfer", type)));
			List<String> findings = catalog.validate();
			assertEquals(1, findings.size(), findings.toString());
			assertTrue(findings.get(0).contains("declared more than once"), findings.get(0));
		}
	}

	@Nested
	@DisplayName("a subscription that names no types")
	class SinkSubscription {

		private final EventSubscription sink = subscription("analytics-db");
		private final String mdb = EventSourceGenerator.consumerSource(sink, catalog());

		@Test
		@DisplayName("emits no selector at all, so a type declared later still reaches it")
		void noSelector() {
			assertNull(sink.selector());
			assertFalse(mdb.contains("messageSelector"),
					"an empty selector string would match nothing; the property must be absent entirely");
		}

		@Test
		@DisplayName("says in the source why there is no selector")
		void explainsItself() {
			assertTrue(mdb.contains("takes everything"), mdb.substring(0, Math.min(2000, mdb.length())));
		}

		@Test
		void handsEveryEventToOneMethod() {
			assertTrue(mdb.contains("onEvent(event);"));
			assertFalse(mdb.contains("switch (String.valueOf(event.getType()))"),
					"there are no declared types to switch on");
		}
	}

	@Nested
	@DisplayName("selector bounds")
	class SelectorBounds {

		@Test
		@DisplayName("several types become one IN clause")
		void severalTypesBecomeAnInClause() {
			EventSubscription subscription = subscription("transfer", "a.b.one", "a.b.two");
			assertEquals(EventPublisher.PROP_TYPE + " IN ('a.b.one', 'a.b.two')", subscription.selector());
		}

		@Test
		@DisplayName("past the readability bound the filtering moves into code")
		void tooManyTypesFallBackToCodeFiltering() {
			List<String> many = new ArrayList<>();
			for (int i = 0; i <= EventSubscription.MAX_SELECTOR_TYPES; i++) {
				many.add("a.b.type" + i);
			}
			EventSubscription subscription = new EventSubscription("wide");
			subscription.setTypes(many);
			assertNull(subscription.selector());
			assertTrue(subscription.selectorRationale().contains("readability bound"));
		}
	}

	@Nested
	@DisplayName("generated producer snippet")
	class Producer {

		private final String snippet = EventSourceGenerator.producerSnippet(fixture());

		@Test
		void buildsThePayloadAndPublishesIt() {
			assertTrue(snippet.contains("Scheduled payload = new Scheduled();"));
			assertTrue(snippet.contains("payload.setWhenText("));
			assertTrue(snippet.contains("\"net.vorpal.attendant.meeting.scheduled\""));
			assertTrue(snippet.contains("EventBus.publish(event);"));
		}

		@Test
		@DisplayName("uses literals that actually compile, so the paste is edited not fixed")
		void usesCompilableLiterals() {
			assertTrue(snippet.contains("Status.CONFIRMED"));
		}
	}

	@Nested
	@DisplayName("generated JSON Schema")
	class Schema {

		private final JsonNode schema = EventSourceGenerator.schema(fixture(), MAPPER);

		@Test
		void declaresTheDialectAndTitle() {
			assertEquals(EventSourceGenerator.SCHEMA_DIALECT, schema.path("$schema").asText());
			assertEquals("object", schema.path("type").asText());
			assertEquals("Meeting Scheduled", schema.path("title").asText());
		}

		@Test
		@DisplayName("keys on the wire name, not the Java name")
		void keysOnTheWireName() {
			JsonNode properties = schema.path("properties");
			assertTrue(properties.has("when_text"));
			assertFalse(properties.has("whenText"));
		}

		@Test
		void typesEachFieldCorrectly() {
			JsonNode properties = schema.path("properties");
			assertEquals("string", properties.path("who").path("type").asText());
			assertEquals("integer", properties.path("attendee_count").path("type").asText());
			assertEquals(3, properties.path("status").path("enum").size());
			assertTrue(properties.path("location").path("properties").has("room"));
			assertEquals("array", properties.path("attendees").path("type").asText());
			assertEquals("string", properties.path("attendees").path("items").path("type").asText());
		}

		@Test
		@DisplayName("a numeric default is emitted as a number, not the string the operator typed")
		void defaultIsCoercedToTheFieldType() {
			assertTrue(schema.path("properties").path("attendee_count").path("default").isInt());
		}

		@Test
		void listsExactlyTheRequiredFields() {
			Set<String> required = new HashSet<>();
			for (JsonNode each : schema.path("required")) {
				required.add(each.asText());
			}
			assertEquals(new HashSet<>(Arrays.asList("who", "when_text")), required);
		}
	}

	@Nested
	@DisplayName("generated sample envelope")
	class Sample {

		@Test
		void isACloudEventCarryingTheDeclaredType() throws Exception {
			EventType declaration = fixture();
			JsonNode parsed = MAPPER.readTree(EventSourceGenerator.sampleEnvelope(declaration, MAPPER));
			assertEquals("1.0", parsed.path("specversion").asText());
			assertEquals(declaration.getType(), parsed.path("type").asText());
			assertFalse(parsed.path("id").asText().isEmpty());
		}

		@Test
		@DisplayName("every sample value satisfies the schema type it is declared as")
		void agreesWithItsOwnSchema() throws Exception {
			EventType declaration = fixture();
			JsonNode data = MAPPER.readTree(EventSourceGenerator.sampleEnvelope(declaration, MAPPER)).path("data");
			JsonNode properties = EventSourceGenerator.schema(declaration, MAPPER).path("properties");

			for (EventField field : declaration.fieldsOrEmpty()) {
				JsonNode value = data.path(field.getName());
				assertFalse(value.isMissingNode(), field.getName() + " missing from the sample");
				assertTrue(jsonTypeMatches(properties.path(field.getName()).path("type").asText(), value),
						field.getName() + " does not match its declared schema type");
			}
		}

		@Test
		void honorsDeclaredDefaultsAndPicksTheFirstEnumValue() throws Exception {
			JsonNode data = MAPPER.readTree(EventSourceGenerator.sampleEnvelope(fixture(), MAPPER)).path("data");
			assertEquals(2, data.path("attendee_count").asInt());
			assertEquals("confirmed", data.path("status").asText());
		}

		private boolean jsonTypeMatches(String schemaType, JsonNode value) {
			switch (schemaType) {
			case "string":
				return value.isTextual();
			case "integer":
				return value.isIntegralNumber();
			case "number":
				return value.isNumber();
			case "boolean":
				return value.isBoolean();
			case "object":
				return value.isObject();
			case "array":
				return value.isArray();
			default:
				return false;
			}
		}
	}

	/// A hand-written class mirroring [SchemaDialect#equivalentDeclaration].
	/// Its only job is to give [SettingsManager#generateSchemaNode] something
	/// equivalent to chew on, so the two schema producers can be compared.
	public static class MeetingScheduled {

		private String who;
		private String whenText;
		private Integer attendeeCount;

		@JsonPropertyDescription("Who the meeting is with.")
		public String getWho() {
			return who;
		}

		public void setWho(String who) {
			this.who = who;
		}

		@JsonProperty("when_text")
		@JsonPropertyDescription("When, as the caller said it.")
		public String getWhenText() {
			return whenText;
		}

		public void setWhenText(String whenText) {
			this.whenText = whenText;
		}

		@JsonProperty("attendee_count")
		@JsonPropertyDescription("How many people are invited.")
		public Integer getAttendeeCount() {
			return attendeeCount;
		}

		public void setAttendeeCount(Integer attendeeCount) {
			this.attendeeCount = attendeeCount;
		}
	}

	@Nested
	@DisplayName("the two schema producers speak one dialect")
	class SchemaDialect {

		private EventType equivalentDeclaration() {
			EventType declaration = new EventType("net.vorpal.attendant.meeting.scheduled");
			declaration.setJavaPackage("com.example.events");
			EventField who = new EventField("who", EventFieldType.STRING, false);
			who.setDescription("Who the meeting is with.");
			EventField whenText = new EventField("when_text", EventFieldType.STRING, false);
			whenText.setDescription("When, as the caller said it.");
			EventField attendeeCount = new EventField("attendee_count", EventFieldType.INTEGER, false);
			attendeeCount.setDescription("How many people are invited.");
			declaration.setFields(Arrays.asList(who, whenText, attendeeCount));
			return declaration;
		}

		@Test
		@DisplayName("same property names, types and descriptions from either producer")
		void bothProducersAgreeOnTheSamePayload() {
			JsonNode designed = EventSourceGenerator.schema(equivalentDeclaration(), MAPPER).path("properties");
			JsonNode reflected = resolveProperties(SettingsManager.generateSchemaNode(MeetingScheduled.class, MAPPER));

			assertTrue(reflected.size() > 0, "victools produced no property set to compare against");
			assertEquals(new HashSet<>(names(designed)), new HashSet<>(names(reflected)));

			for (String name : names(designed)) {
				assertEquals(reflected.path(name).path("type").asText(), designed.path(name).path("type").asText(),
						"type disagrees for " + name);
				assertEquals(reflected.path(name).path("description").asText(),
						designed.path(name).path("description").asText(), "description disagrees for " + name);
			}
		}

		/// Dig the property map out of a victools schema, which may put the
		/// object body behind a `$ref` into `$defs` rather than inline at the
		/// root. The designer has no reason to imitate that idiom for a flat
		/// payload, so the comparison is on the property set rather than whole
		/// documents.
		private JsonNode resolveProperties(JsonNode schema) {
			if (schema.has("properties")) {
				return schema.path("properties");
			}
			JsonNode defs = schema.has("$defs") ? schema.path("$defs") : schema.path("definitions");
			for (JsonNode candidate : defs) {
				if (candidate.has("properties")) {
					return candidate.path("properties");
				}
			}
			return schema.path("properties");
		}

		private List<String> names(JsonNode objectNode) {
			List<String> names = new ArrayList<>();
			objectNode.fieldNames().forEachRemaining(names::add);
			return names;
		}
	}

	@Nested
	@DisplayName("downloadable Maven module")
	class ModuleZip {

		private Set<String> entriesOf(byte[] zipped) throws Exception {
			Set<String> entries = new HashSet<>();
			try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zipped))) {
				for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
					entries.add(entry.getName());
				}
			}
			return entries;
		}

		@Test
		@DisplayName("an event type ships its payload contract — and no consumer")
		void theTypeModuleCarriesThePayloadContract() throws Exception {
			Set<String> entries = entriesOf(EventSourceGenerator.moduleZip(fixture(), MAPPER));

			String base = "net-vorpal-attendant-meeting-scheduled/";
			assertTrue(entries.contains(base + "pom.xml"));
			assertTrue(entries.contains(base + "src/main/java/com/example/events/Scheduled.java"));
			assertTrue(entries
					.contains(base + "src/main/resources/schema/net-vorpal-attendant-meeting-scheduled.schema.json"));
			assertTrue(entries.contains(base + "sample.json"));
			assertTrue(entries.contains(base + "README.md"));

			assertFalse(entries.contains(base + "src/main/java/com/example/events/ScheduledListener.java"),
					"a consumer belongs to a subscriber, not to an event — shipping one here is what made it "
							+ "look as though an event had exactly one consumer");
		}

		@Test
		@DisplayName("a subscription ships the consumer plus the payloads it binds to")
		void theSubscriptionModuleCarriesTheConsumer() throws Exception {
			EventSubscription subscription = subscription("attendant-meetings",
					"net.vorpal.attendant.meeting.scheduled");
			Set<String> entries = entriesOf(
					EventSourceGenerator.subscriptionModuleZip(subscription, catalog(), MAPPER));

			String base = "attendant-meetings-consumer/";
			assertTrue(entries.contains(base + "pom.xml"));
			assertTrue(entries.contains(base + "src/main/java/com/example/consumer/AttendantMeetingsListener.java"));
			assertTrue(entries.contains(base + "src/main/java/com/example/events/Scheduled.java"));
			assertTrue(entries.contains(base + "README.md"));
		}
	}

	@Nested
	@DisplayName("catalog lookup")
	class Catalog {

		@Test
		void findsDeclaredTypesAndToleratesTheRest() {
			EventType declaration = fixture();
			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(Arrays.asList(declaration));

			assertSame(declaration, catalog.findType(declaration.getType()));
			assertNull(catalog.findType("no.such.type"));
			assertNull(catalog.findType(null));
			assertTrue(new EventCatalog().typesOrEmpty().isEmpty());
		}

		@Test
		void defaultsMatchTheEventBusConstants() {
			EventCatalog catalog = new EventCatalog();
			assertEquals(EventBus.CONNECTION_FACTORY_JNDI, catalog.getConnectionFactoryJndi());
		}

		@Test
		@DisplayName("validation defaults to WARN so it can be switched on against live traffic")
		void validationDefaultsToWarn() {
			assertEquals(ValidationPolicy.WARN, new EventCatalog().getValidation());
		}

		@Test
		void aTypeWithoutItsOwnDestinationFallsBackToTheCatalogDefault() {
			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(Arrays.asList(fixture()));
			assertEquals(catalog.getDefaultDestinationJndi(), catalog.destinationFor(new EventType("a.b.c")));
			assertEquals("jms/BladeEventBusTopic", catalog.destinationFor(fixture()));
		}
	}

	@Nested
	@DisplayName("incomplete declarations")
	class Incomplete {

		@Test
		@DisplayName("a live preview regenerates on every keystroke, so nothing may throw")
		void halfAuthoredDeclarationsNeverThrow() {
			EventType bare = new EventType();
			EventSubscription unnamed = new EventSubscription();
			assertDoesNotThrow(() -> {
				EventSourceGenerator.javaSource(bare);
				EventSourceGenerator.producerSnippet(bare);
				EventSourceGenerator.schema(bare, MAPPER);
				EventSourceGenerator.sampleEnvelope(bare, MAPPER);
				EventSourceGenerator.javaSource(null);
				EventSourceGenerator.schema(null, MAPPER);
				EventSourceGenerator.consumerSource(unnamed, null);
				EventSourceGenerator.consumerSource(null, null);
				EventSourceGenerator.consumerSource(new EventSubscription("half-typed"), null);
			});
		}

		/// A subscription may name a type before that type is declared — the two
		/// are separate edits and neither ordering should be an error.
		///
		/// What the generator does with it is a deliberate split: the **selector**
		/// names the type, because the operator said they want it and a selector
		/// that quietly dropped it would take everything instead; the **switch**
		/// does not, because there is no payload class to bind to and inventing one
		/// would emit source that will not compile. The gap surfaces at runtime as
		/// the `default:` warning rather than silently.
		@Test
		@DisplayName("a subscription naming a type the catalog does not declare still generates")
		void undeclaredTypesSelectButDoNotDispatch() {
			EventSubscription subscription = subscription("early-bird", "not.yet.declared");
			String mdb = EventSourceGenerator.consumerSource(subscription, catalog());

			assertTrue(mdb.contains("public class EarlyBirdListener implements MessageListener"));
			assertTrue(mdb.contains("propertyValue = \"" + subscription.selector() + "\""),
					"the operator asked for this type; the selector must still narrow to it");
			assertFalse(mdb.contains("case \"not.yet.declared\""),
					"an undeclared type has no payload class, so it cannot be dispatched");

			EventCatalog catalog = catalog();
			catalog.setSubscriptions(Arrays.asList(subscription));
			assertEquals(1, catalog.validate().size(), catalog.validate().toString());
			assertTrue(catalog.validate().get(0).contains("does not declare"),
					"the operator still has to be told, in the console rather than in a compile error");
		}

		@Test
		void aFieldlessTypeStillGeneratesACompilableShell() {
			EventType noFields = new EventType("a.b.thing");
			noFields.setJavaPackage("com.example");
			String java = EventSourceGenerator.javaSource(noFields);
			assertNotNull(java);
			assertTrue(java.contains("public class Thing implements Serializable"));
			assertTrue(java.trim().endsWith("}"));
		}
	}
}
