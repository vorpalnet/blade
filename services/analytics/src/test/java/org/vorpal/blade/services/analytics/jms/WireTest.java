package org.vorpal.blade.services.analytics.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.AnalyticsEvent;
import org.vorpal.blade.framework.v3.events.AnalyticsEventMapper;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The sink's side of the wire contract.
///
/// **What this covers and what it cannot.** Everything else in
/// [AnalyticsEventListener] needs an `EntityManager`, a JMS session and a
/// WebLogic MBean server — it can only be exercised on a live domain. [Wire] is
/// where the payload is actually interpreted, so it is where the mistakes are,
/// and it is pure. These tests say the producer and the sink agree about the
/// bytes between them; they say nothing about whether a row reaches a database.
class WireTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static JsonNode publish(AnalyticsEvent event) {
		return event.toCloudEvent("/blade/app", "app", "dom", "srv", new Date(1779000000000L)).getData();
	}

	@Nested
	@DisplayName("the correlator survives the trip")
	class Correlator {

		@Test
		@DisplayName("what the producer sent is what the sink reads")
		void roundTrips() {
			long sent = 0x1A2B3C4DL;
			JsonNode data = publish(new AnalyticsEvent("callStarted", Long.valueOf(sent), new Date()));
			assertEquals(sent, Wire.vorpalId(data));
		}

		/// The producer formats the id with `%08X`, which pads to eight
		/// characters but does not stop at eight. A value above 32 bits emits
		/// more, and `Integer.parseInt` on that overflows and throws — taking the
		/// whole event down rather than one field.
		@Test
		@DisplayName("an id above 32 bits round-trips instead of overflowing")
		void largeIdsDoNotOverflow() {
			long large = 0xFFFFFFFFL;
			JsonNode data = publish(new AnalyticsEvent("callStarted", Long.valueOf(large), new Date()));
			assertEquals(large, Wire.vorpalId(data));

			long beyond = 0x1FFFFFFFFL;
			JsonNode beyondData = publish(new AnalyticsEvent("callStarted", Long.valueOf(beyond), new Date()));
			assertEquals(beyond, Wire.vorpalId(beyondData));
		}

		@Test
		@DisplayName("a sessionless event reads as no correlator, not as a parse failure")
		void sessionlessReadsAsZero() {
			JsonNode data = publish(new AnalyticsEvent("start", null, null));
			assertEquals(0L, Wire.vorpalId(data));
			assertNull(Wire.text(data, "vorpalId"));
		}
	}

	@Nested
	@DisplayName("times")
	class Times {

		/// The one that would have been catastrophic and silent: the resolver
		/// matches a session on `(cluster_name, vorpal_id, created)`, so a
		/// millisecond lost between the producer and the sink means every lookup
		/// misses and every event inserts a fresh session row.
		@Test
		@DisplayName("milliseconds survive, because the session natural key depends on them")
		void millisecondsSurvive() {
			Date started = new Date(1780000000123L);
			JsonNode data = publish(new AnalyticsEvent("callStarted", Long.valueOf(1L), started));

			Date read = Wire.instant(data, "startedAt");
			assertNotNull(read);
			assertEquals(started.getTime(), read.getTime(), "a truncated instant makes every session lookup miss");
		}

		@Test
		@DisplayName("occurredAt is the event's own time, distinct from the call's birth")
		void occurredAtIsDistinct() {
			Date started = new Date(1780000000000L);
			Date occurred = new Date(1780000060000L);
			JsonNode data = publish(new AnalyticsEvent("callCompleted", Long.valueOf(1L), started, occurred));

			assertEquals(started.getTime(), Wire.instant(data, "startedAt").getTime());
			assertEquals(occurred.getTime(), Wire.instant(data, "occurredAt").getTime());
		}

		@Test
		@DisplayName("an absent or unparseable time costs a column, not the row")
		void badTimesReturnNull() {
			JsonNode data = publish(new AnalyticsEvent("callStarted", Long.valueOf(1L), null));
			assertNull(Wire.instant(data, "stoppedAt"), "absent");
			assertNull(Wire.instant(MAPPER.createObjectNode().put("t", "not-a-time"), "t"), "unparseable");
		}
	}

	@Nested
	@DisplayName("the application identity")
	class ApplicationIdentity {

		/// The natural key that replaced a producer-minted random id. All four
		/// fields have to be present on every call-scoped event or the sink has
		/// nothing to resolve the `NOT NULL` foreign key with.
		@Test
		@DisplayName("every call-scoped event carries all four fields")
		void allFourFieldsArePresent() {
			JsonNode data = publish(new AnalyticsEvent("callStarted", Long.valueOf(1L), new Date()));

			assertEquals("app", Wire.text(data, "appName"));
			assertEquals("dom", Wire.text(data, "domain"));
			assertEquals("srv", Wire.text(data, "server"));
			assertNotNull(Wire.instant(data, "appStartedAt"));
		}

		/// A session key may be the first thing the sink sees for a call, so it
		/// has to be able to resolve the session — and a session row carries a
		/// NOT NULL foreign key to the application. Without these fields the old
		/// path inserted a stub with `application_id = 0`, which violates
		/// `session_fk1` unless a row with id 0 happens to exist.
		@Test
		@DisplayName("and so does a session key, which used to arrive with none")
		void sessionKeyCarriesThemToo() {
			JsonNode data = AnalyticsEventMapper.sessionKey("/blade/app", 0x1A2B3C4DL, new Date(), "caller",
					"sip:alice@example", "app", "dom", "srv", new Date(1779000000000L)).getData();

			assertEquals("app", Wire.text(data, "appName"));
			assertEquals("dom", Wire.text(data, "domain"));
			assertEquals("srv", Wire.text(data, "server"));
			assertNotNull(Wire.instant(data, "appStartedAt"));
			assertEquals(0x1A2B3C4DL, Wire.vorpalId(data));
		}
	}

	@Nested
	@DisplayName("attributes")
	class Attributes {

		@Test
		@DisplayName("arrive as an array of name/value pairs, in order")
		void arriveAsPairs() {
			AnalyticsEvent event = new AnalyticsEvent("callStarted", Long.valueOf(1L), new Date());
			Map<String, String> expected = new LinkedHashMap<>();
			expected.put("caller", "alice");
			expected.put("callee", "bob");
			expected.forEach(event::addAttribute);

			JsonNode array = publish(event).path("attributes");
			assertTrue(array.isArray());
			assertEquals(2, array.size());
			assertEquals("caller", array.get(0).path("name").asText());
			assertEquals("alice", array.get(0).path("value").asText());
		}

		@Test
		@DisplayName("an over-long value is trimmed to its column, not dropped")
		void oversizedValuesAreTrimmed() {
			StringBuilder wide = new StringBuilder();
			for (int i = 0; i < 2000; i++) {
				wide.append('x');
			}
			assertEquals(1024, Wire.truncate(wide.toString(), 1024).length());
			assertEquals("short", Wire.truncate("short", 1024));
			assertNull(Wire.truncate(null, 1024));
		}
	}

	@Nested
	@DisplayName("naming a row")
	class Naming {

		/// The framework's own events carry `eventName` in the payload, so
		/// `event_types` keeps storing `transferRequested` exactly as it did
		/// before the type existed — and reports built on it keep working.
		@Test
		@DisplayName("a framework event still names itself the way the database expects")
		void frameworkEventsKeepTheirShortName() {
			CloudEvent published = new AnalyticsEvent("transferRequested", Long.valueOf(1L), new Date())
					.toCloudEvent("/blade/app", "app", "dom", "srv", new Date());

			assertEquals(BladeEventTypes.TRANSFER_REQUESTED, published.getType());
			assertEquals("transferRequested", published.getData().path("eventName").asText(),
					"the type is new; the name in event_types must not change under existing reports");
		}

		@Test
		@DisplayName("a type with no eventName falls back to its last segment")
		void undeclaredTypesUseTheLastSegment() {
			assertEquals("scheduled", Wire.shortName("net.vorpal.attendant.meeting.scheduled"));
			assertEquals("bare", Wire.shortName("bare"));
			assertEquals("unknown", Wire.shortName(null));
			assertEquals("trailing.", Wire.shortName("trailing."));
		}
	}
}
