package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

/// The producer's side of the one-system change: [AnalyticsEvent], the fact the
/// framework assembles in two passes and then closes into a [CloudEvent].
///
/// This replaced the JPA `Event` entity the producer used to fill in — a row,
/// with a generated primary key and three surrogate foreign keys that only the
/// consumer's database could assign. The tests below are mostly about the
/// properties that made that shape wrong.
class AnalyticsEventTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final JsonSchemaFactory FACTORY = JsonSchemaFactory
			.getInstance(SpecVersion.VersionFlag.V202012);

	private static final long VORPAL_ID = 0x1A2B3C4DL;
	private static final Date CALL_STARTED = new Date(1780000000000L);
	private static final Date APP_STARTED = new Date(1779000000000L);

	private static CloudEvent close(AnalyticsEvent event) {
		return event.toCloudEvent("/blade/app", "app", "dom", "srv", APP_STARTED);
	}

	@Nested
	@DisplayName("two-pass assembly")
	class Assembly {

		@Test
		@DisplayName("origin attributes then destination attributes, in order")
		void attributesAccumulate() {
			AnalyticsEvent event = new AnalyticsEvent("callAnswered", Long.valueOf(VORPAL_ID), CALL_STARTED);
			event.addAttribute("caller", "alice");
			event.addAttribute("callee", "bob");

			JsonNode attributes = close(event).getData().path("attributes");
			assertEquals(2, attributes.size());
			assertEquals("caller", attributes.get(0).path("name").asText());
			assertEquals("callee", attributes.get(1).path("name").asText());
		}

		@Test
		@DisplayName("a repeated attribute name overwrites rather than duplicating")
		void repeatedNamesOverwrite() {
			AnalyticsEvent event = new AnalyticsEvent("callAnswered", Long.valueOf(VORPAL_ID), CALL_STARTED);
			event.addAttribute("caller", "alice");
			event.addAttribute("caller", "carol");

			JsonNode attributes = close(event).getData().path("attributes");
			assertEquals(1, attributes.size(), "the payload declares attribute names unique");
			assertEquals("carol", attributes.get(0).path("value").asText());
		}

		@Test
		@DisplayName("the attribute map is not writable from outside")
		void attributesAreReadOnly() {
			AnalyticsEvent event = new AnalyticsEvent("callAnswered", null, null);
			assertThrows(UnsupportedOperationException.class, () -> event.getAttributes().put("x", "y"));
		}
	}

	@Nested
	@DisplayName("the correlator")
	class Correlator {

		@Test
		@DisplayName("both halves are attached together, never one without the other")
		void correlateWithSetsBoth() {
			AnalyticsEvent event = new AnalyticsEvent("apiCall", null, null);
			assertNull(close(event).getSubject(), "no correlator yet, so no subject");

			event.correlateWith(Long.valueOf(VORPAL_ID), CALL_STARTED);
			String subject = close(event).getSubject();

			assertTrue(subject.startsWith("1A2B3C4D."), subject);
			assertTrue(subject.length() > "1A2B3C4D.".length(),
					"the call's birth instant must be present: a 32-bit id alone is reused between calls");
		}

		@Test
		@DisplayName("two calls that reused one Vorpal-ID get different subjects")
		void reusedIdsStayDistinct() {
			AnalyticsEvent first = new AnalyticsEvent("callStarted", Long.valueOf(VORPAL_ID), new Date(1780000000000L));
			AnalyticsEvent second = new AnalyticsEvent("callStarted", Long.valueOf(VORPAL_ID), new Date(1780000999000L));
			assertNotEquals(close(first).getSubject(), close(second).getSubject());
		}

		@Test
		@DisplayName("an event with no call is legal and carries no subject")
		void sessionlessIsLegal() {
			CloudEvent event = close(new AnalyticsEvent("start", null, null));
			assertNull(event.getSubject());
			assertFalse(event.getData().has("vorpalId"));
		}
	}

	@Nested
	@DisplayName("the name decides the type")
	class Typing {

		@Test
		@DisplayName("each of the framework's eleven names gets a type of its own")
		void frameworkNamesGetTheirOwnTypes() {
			String[] names = { "callStarted", "callAnswered", "callConnected", "callCompleted", "callAbandoned",
					"callDeclined", "transferRequested", "transferInitiated", "transferCompleted", "transferDeclined",
					"transferAbandoned" };

			List<String> types = new ArrayList<>();
			for (String name : names) {
				String type = close(new AnalyticsEvent(name, Long.valueOf(VORPAL_ID), CALL_STARTED)).getType();
				assertNotEquals(BladeEventTypes.CALL_EVENT, type, name);
				assertFalse(types.contains(type), name + " collides with an earlier name on " + type);
				types.add(type);
			}
		}

		@Test
		@DisplayName("an operator's own name takes the fallback, with the name in the payload")
		void operatorNamesFallBack() {
			CloudEvent event = close(new AnalyticsEvent("agentWrapUp", Long.valueOf(VORPAL_ID), CALL_STARTED));
			assertEquals(BladeEventTypes.CALL_EVENT, event.getType());
			assertEquals("agentWrapUp", event.getData().path("eventName").asText());
		}

		/// The point of the whole change, stated as a test: a transfer app can
		/// select exactly the events it acts on.
		@Test
		@DisplayName("a transfer subscription's selector matches transfer events and nothing else")
		void aTransferSubscriptionSelectsPrecisely() {
			EventSubscription transfer = new EventSubscription("transfer");
			transfer.setTypes(java.util.Arrays.asList(BladeEventTypes.TRANSFER_REQUESTED));
			String selector = transfer.selector();

			String referType = close(new AnalyticsEvent("transferRequested", Long.valueOf(VORPAL_ID), CALL_STARTED))
					.getType();
			String callType = close(new AnalyticsEvent("callStarted", Long.valueOf(VORPAL_ID), CALL_STARTED)).getType();

			assertTrue(selector.contains("'" + referType + "'"), selector);
			assertFalse(selector.contains("'" + callType + "'"),
					"a transfer consumer must not have to receive every call event and filter in code");
		}
	}

	@Nested
	@DisplayName("times")
	class Times {

		@Test
		@DisplayName("when it happened is a field; when it was published is the envelope")
		void occurredAtIsNotPublishTime() {
			Date occurred = new Date(1780000005000L);
			AnalyticsEvent event = new AnalyticsEvent("callAnswered", Long.valueOf(VORPAL_ID), CALL_STARTED, occurred);

			CloudEvent published = close(event);
			String occurredAt = published.getData().path("occurredAt").asText();

			// The instant itself, not a hand-written string: what matters is that
			// the value survives the trip to ISO-8601 without losing precision or
			// picking up the publisher's time zone.
			assertEquals(occurred.getTime(), java.time.Instant.parse(occurredAt).toEpochMilli());
			assertTrue(occurredAt.endsWith("Z"), occurredAt);
			assertNotEquals(occurredAt, published.getTime(),
					"collapsing the two would stamp every event in a call with the publish instant");
		}

		@Test
		@DisplayName("the call's birth instant is separate from when this event happened")
		void callBirthIsItsOwnField() {
			JsonNode data = close(new AnalyticsEvent("callCompleted", Long.valueOf(VORPAL_ID), CALL_STARTED,
					new Date(1780000060000L))).getData();

			assertNotEquals(data.path("startedAt").asText(), data.path("occurredAt").asText());
		}
	}

	/// Every envelope the producer can emit is validated against the schema the
	/// catalog declares for its type.
	///
	/// Two files that have to agree — the mapper builds the payload, the catalog
	/// describes it — and with validation defaulting to WARN, a disagreement is a
	/// log line nobody reads rather than a failure anyone notices.
	@Nested
	@DisplayName("every produced envelope satisfies its declared schema")
	class Contract {

		@Test
		void allElevenPlusTheFallback() throws Exception {
			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(BladeEventCatalog.analyticsTypes());

			String[] names = { "callStarted", "callAnswered", "callConnected", "callCompleted", "callAbandoned",
					"callDeclined", "transferRequested", "transferInitiated", "transferCompleted", "transferDeclined",
					"transferAbandoned", "agentWrapUp" };

			for (String name : names) {
				AnalyticsEvent event = new AnalyticsEvent(name, Long.valueOf(VORPAL_ID), CALL_STARTED);
				event.addAttribute("caller", "alice");
				CloudEvent published = close(event);

				EventType declaration = catalog.findType(published.getType());
				assertTrue(declaration != null, "the catalog does not declare " + published.getType());

				JsonSchema schema = FACTORY.getSchema(EventSourceGenerator.schema(declaration, MAPPER));
				java.util.Set<com.networknt.schema.ValidationMessage> errors = schema.validate(published.getData());
				assertTrue(errors.isEmpty(), name + " fails the schema of " + published.getType() + ": " + errors);
			}
		}

		@Test
		@DisplayName("and a sessionless one, which the correlator being optional is for")
		void sessionlessPassesToo() {
			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(BladeEventCatalog.analyticsTypes());

			CloudEvent published = close(new AnalyticsEvent("callStarted", null, null));
			EventType declaration = catalog.findType(published.getType());

			JsonSchema schema = FACTORY.getSchema(EventSourceGenerator.schema(declaration, MAPPER));
			assertTrue(schema.validate(published.getData()).isEmpty(),
					"rejecting a sessionless event at the ingress would turn a logging gap into a failed "
							+ "publish on the SIP container thread");
		}
	}
}
