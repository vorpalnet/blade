package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

/// The analytics-to-CloudEvents translation.
///
/// The load-bearing test is [Contract#everyMappedEventSatisfiesItsDeclaredSchema]:
/// it validates each produced envelope against the schema the catalog declares
/// for that type. Two independent things — the mapper and the catalog — have to
/// agree about the payload, and nothing else would notice if they stopped.
class AnalyticsEventMapperTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final JsonSchemaFactory FACTORY =
			JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

	private static final long VORPAL_ID = 0x1A2B3C4DL;
	private static final Date STARTED = new Date(1780000000000L);
	private static final Date APP_STARTED = new Date(1779000000000L);

	@Nested
	@DisplayName("the correlator")
	class Correlator {

		@Test
		@DisplayName("subject carries the id and its birth instant, because ids are reused")
		void subjectIsIdPlusBirthInstant() {
			String subject = AnalyticsEventMapper.subject(VORPAL_ID, STARTED);
			assertTrue(subject.startsWith("1A2B3C4D."), subject);
			assertTrue(subject.length() > "1A2B3C4D.".length(), "the birth instant must be present");
		}

		@Test
		@DisplayName("two calls reusing one Vorpal-ID get different subjects")
		void reusedIdsAreDistinguished() {
			String first = AnalyticsEventMapper.subject(VORPAL_ID, new Date(1780000000000L));
			String second = AnalyticsEventMapper.subject(VORPAL_ID, new Date(1780000999000L));
			assertNotEquals(first, second,
					"a 32-bit id checked only against live sessions is reused; the pair must still be unique");
		}

		@Test
		void aBareIdStillProducesASubject() {
			assertEquals("1A2B3C4D", AnalyticsEventMapper.subject(VORPAL_ID, null));
		}
	}

	@Nested
	@DisplayName("start and stop are distinct types")
	class StartStop {

		@Test
		void sessionStartAndStopDiffer() {
			CloudEvent start = AnalyticsEventMapper.session("/blade/app", VORPAL_ID, STARTED, null, "app", "d", "s",
					APP_STARTED);
			CloudEvent stop = AnalyticsEventMapper.session("/blade/app", VORPAL_ID, STARTED, new Date(), "app", "d",
					"s", APP_STARTED);

			assertEquals(BladeEventTypes.SESSION_STARTED, start.getType());
			assertEquals(BladeEventTypes.SESSION_STOPPED, stop.getType());
			assertEquals(start.getSubject(), stop.getSubject(), "both are the same call");
		}

		@Test
		void applicationStartAndStopDiffer() {
			CloudEvent start = AnalyticsEventMapper.application("/blade/app", "app", "d", "s", "h", "t", APP_STARTED,
					null);
			CloudEvent stop = AnalyticsEventMapper.application("/blade/app", "app", "d", "s", "h", "t", APP_STARTED,
					new Date());
			assertEquals(BladeEventTypes.APPLICATION_STARTED, start.getType());
			assertEquals(BladeEventTypes.APPLICATION_STOPPED, stop.getType());
		}

		@Test
		@DisplayName("application lifecycle is not call-scoped, so it carries no subject")
		void applicationHasNoSubject() {
			CloudEvent event = AnalyticsEventMapper.application("/blade/app", "app", "d", "s", null, null,
					APP_STARTED, null);
			assertNull(event.getSubject());
		}
	}

	@Nested
	@DisplayName("session keys stop smuggling")
	class SessionKeys {

		@Test
		@DisplayName("no session_id appears on the wire at all")
		void noSessionIdField() {
			CloudEvent event = AnalyticsEventMapper.sessionKey("/blade/app", VORPAL_ID, STARTED, "caller",
					"sip:alice@example", "app", "d", "s", APP_STARTED);
			JsonNode data = event.getData();
			assertFalse(data.has("sessionId"), "the old wire object carried the vorpal-id in this slot");
			assertFalse(data.has("session_id"));
			assertEquals("caller", data.path("name").asText());
			assertEquals("sip:alice@example", data.path("value").asText());
			assertTrue(event.getSubject().startsWith("1A2B3C4D."), "the correlator lives in the envelope");
		}
	}

	@Nested
	@DisplayName("times")
	class Times {

		@Test
		@DisplayName("envelope time is publish time; the call's birth instant is a field")
		void publishTimeIsNotCallBirth() throws Exception {
			CloudEvent event = AnalyticsEventMapper.session("/blade/app", VORPAL_ID, STARTED, null, "app", "d", "s",
					APP_STARTED);
			assertNotEquals(String.valueOf(STARTED.getTime()), event.getTime());
			assertTrue(event.getTime().endsWith("Z"));
			assertTrue(event.getData().path("startedAt").asText().startsWith("2026-"),
					"the call birth instant travels as an explicit field");
		}

		@Test
		@DisplayName("domain times are ISO instants, not epoch millis")
		void domainTimesAreIsoInstants() {
			CloudEvent event = AnalyticsEventMapper.callEvent("/blade/app", "callStarted", Long.valueOf(VORPAL_ID),
					STARTED, new Date(1780000005000L), "app", "d", "s", APP_STARTED, null);
			String occurred = event.getData().path("occurredAt").asText();
			assertTrue(occurred.contains("T") && occurred.endsWith("Z"), occurred);
		}
	}

	@Nested
	@DisplayName("call events")
	class CallEvents {

		@Test
		@DisplayName("attributes flatten from entity-attribute-value into name/value pairs")
		void attributesBecomeAnArray() {
			Map<String, String> attributes = new LinkedHashMap<>();
			attributes.put("caller", "alice");
			attributes.put("callee", "bob");

			CloudEvent event = AnalyticsEventMapper.callEvent("/blade/app", "callStarted", Long.valueOf(VORPAL_ID),
					STARTED, STARTED, "app", "d", "s", APP_STARTED, attributes);

			JsonNode array = event.getData().path("attributes");
			assertTrue(array.isArray());
			assertEquals(2, array.size());
			assertEquals("caller", array.get(0).path("name").asText());
			assertEquals("alice", array.get(0).path("value").asText());
		}

		@Test
		@DisplayName("a framework name gets its own type; an operator's name gets the fallback")
		void theNameDecidesTheType() {
			CloudEvent framework = AnalyticsEventMapper.callEvent("/blade/app", "transferRequested",
					Long.valueOf(VORPAL_ID), STARTED, STARTED, "app", "d", "s", APP_STARTED, null);
			CloudEvent operator = AnalyticsEventMapper.callEvent("/blade/app", "agentWrapUp", Long.valueOf(VORPAL_ID),
					STARTED, STARTED, "app", "d", "s", APP_STARTED, null);

			assertEquals(BladeEventTypes.TRANSFER_REQUESTED, framework.getType(),
					"a transfer consumer must be able to select on this without receiving every call event");
			assertEquals(BladeEventTypes.CALL_EVENT, operator.getType(),
					"an operator's own name has no declaration to select on, so it takes the fallback");

			assertEquals("transferRequested", framework.getData().path("eventName").asText(),
					"the name stays in the payload either way");
			assertEquals("agentWrapUp", operator.getData().path("eventName").asText());
		}

		@Test
		@DisplayName("a sessionless event is legal and carries no subject")
		void sessionlessEvent() {
			CloudEvent event = AnalyticsEventMapper.callEvent("/blade/app", "start", null, null, STARTED, "app", "d",
					"s", APP_STARTED, null);
			assertNull(event.getSubject());
			assertFalse(event.getData().has("vorpalId"));
		}
	}

	@Nested
	@DisplayName("the mapper and the catalog agree")
	class Contract {

		/// Validate every produced envelope against the schema the catalog
		/// declares for its type.
		///
		/// The mapper builds payloads and the catalog describes them, in two
		/// separate files. If either drifts, an event would be published that
		/// its own declared schema rejects — and with validation set to WARN
		/// that is a log line nobody reads.
		@Test
		void everyMappedEventSatisfiesItsDeclaredSchema() throws Exception {
			Map<String, String> attributes = new LinkedHashMap<>();
			attributes.put("caller", "alice");

			CloudEvent[] events = {
					AnalyticsEventMapper.application("/blade/app", "app", "dom", "srv", "host", "tenant",
							APP_STARTED, null),
					AnalyticsEventMapper.application("/blade/app", "app", "dom", "srv", "host", "tenant",
							APP_STARTED, new Date()),
					AnalyticsEventMapper.session("/blade/app", VORPAL_ID, STARTED, null, "app", "dom", "srv",
							APP_STARTED),
					AnalyticsEventMapper.session("/blade/app", VORPAL_ID, STARTED, new Date(), "app", "dom", "srv",
							APP_STARTED),
					AnalyticsEventMapper.sessionKey("/blade/app", VORPAL_ID, STARTED, "caller", "alice", "app", "dom",
							"srv", APP_STARTED),
					AnalyticsEventMapper.callEvent("/blade/app", "callStarted", Long.valueOf(VORPAL_ID), STARTED,
							STARTED, "app", "dom", "srv", APP_STARTED, attributes),
					AnalyticsEventMapper.callEvent("/blade/app", "transferRequested", Long.valueOf(VORPAL_ID), STARTED,
							STARTED, "app", "dom", "srv", APP_STARTED, attributes),
					AnalyticsEventMapper.callEvent("/blade/app", "agentWrapUp", Long.valueOf(VORPAL_ID), STARTED,
							STARTED, "app", "dom", "srv", APP_STARTED, attributes) };

			EventCatalog catalog = new EventCatalog();
			catalog.setTypes(BladeEventCatalog.analyticsTypes());

			for (CloudEvent event : events) {
				EventType declaration = catalog.findType(event.getType());
				assertTrue(declaration != null, "the catalog does not declare " + event.getType());

				JsonSchema schema = FACTORY.getSchema(EventSourceGenerator.schema(declaration, MAPPER));
				java.util.Set<com.networknt.schema.ValidationMessage> errors = schema.validate(event.getData());
				assertTrue(errors.isEmpty(), event.getType() + " fails its own declared schema: " + errors);
			}
		}
	}
}
