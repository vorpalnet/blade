package org.vorpal.blade.framework.v3.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Covers the per-app event-bus settings and the registry they drive.
///
/// The point of these is the failure they prevent: `EventBus`'s publisher
/// registry is **per-WAR** static state, because the framework jar ships inside
/// each WAR rather than in the shared library. Before an app stands up its own
/// publisher, `EventBus.publish` finds nothing and returns silently — which
/// looks exactly like "no events yet."
class EventBusSettingsTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Nested
	@DisplayName("settings defaults")
	class Defaults {

		@Test
		@DisplayName("publishing is off until an app asks for it")
		void disabledByDefault() {
			EventBusSettings settings = new EventBusSettings();
			assertFalse(Boolean.TRUE.equals(settings.isEnabled()),
					"an app that does not publish should not hold a JMS connection");
		}

		@Test
		void jndiNamesDefaultToTheFrameworkConstants() {
			EventBusSettings settings = new EventBusSettings();
			assertEquals(EventBus.CONNECTION_FACTORY_JNDI, settings.getConnectionFactoryJndi());
			assertEquals(EventBus.TOPIC_JNDI, settings.getDestinationJndi());
		}

		@Test
		@DisplayName("blanking a JNDI name falls back rather than publishing to nowhere")
		void blankNamesFallBack() {
			EventBusSettings settings = new EventBusSettings();
			settings.setConnectionFactoryJndi("");
			settings.setDestinationJndi(null);
			assertEquals(EventBus.CONNECTION_FACTORY_JNDI, settings.getConnectionFactoryJndi());
			assertEquals(EventBus.TOPIC_JNDI, settings.getDestinationJndi());
		}

		@Test
		void aNullEnabledIsFalseNotNull() {
			EventBusSettings settings = new EventBusSettings();
			settings.setEnabled(null);
			assertNotNull(settings.isEnabled());
			assertFalse(settings.isEnabled());
		}
	}

	@Nested
	@DisplayName("the config block")
	class ConfigBlock {

		@Test
		@DisplayName("rides an app's Configuration beside analytics, and round-trips")
		void roundTripsThroughConfiguration() throws Exception {
			EventBusSettings settings = new EventBusSettings();
			settings.setEnabled(Boolean.TRUE);
			settings.setDestinationJndi("jms/SomeOtherTopic");

			Configuration config = new Configuration();
			config.setEvents(settings);

			JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(config));
			assertTrue(json.has("events"), "the block must serialize under 'events'");
			assertTrue(json.path("events").path("enabled").asBoolean());
			assertEquals("jms/SomeOtherTopic", json.path("events").path("destinationJndi").asText());

			Configuration parsed = MAPPER.readValue(MAPPER.writeValueAsString(config), Configuration.class);
			assertNotNull(parsed.getEvents());
			assertTrue(parsed.getEvents().isEnabled());
			assertEquals("jms/SomeOtherTopic", parsed.getEvents().getDestinationJndi());
		}

		@Test
		@DisplayName("an app config with no events block still parses")
		void absentBlockIsTolerated() throws Exception {
			Configuration parsed = MAPPER.readValue("{\"version\":1}", Configuration.class);
			assertNull(parsed.getEvents(), "absent means absent; the framework defaults it at build time");
		}
	}

	@Nested
	@DisplayName("the publisher registry")
	class Registry {

		@Test
		@DisplayName("publishing with nothing registered is a silent no-op — the bug this phase fixes")
		void publishWithoutAPublisherDoesNothing() throws Exception {
			EventBus.unregisterAll();
			assertFalse(EventBus.isReady(), "no publisher installed");

			CloudEvent event = CloudEvent.create("org.vorpal.test.thing", "/blade/test", "subject-1", null);
			EventBus.publish(event); // must not throw

			assertNull(EventBus.publisherFor(null));
		}

		@Test
		void registeringMakesTheBusReadyForThatDestination() {
			EventBus.unregisterAll();
			try {
				EventPublisher publisher = new EventPublisher(EventBus.CONNECTION_FACTORY_JNDI, "jms/TestDestination");
				EventBus.register(publisher);

				assertSame(publisher, EventBus.publisherFor("jms/TestDestination"));
				assertTrue(EventBus.isReady("jms/TestDestination"));
				assertTrue(EventBus.registeredDestinations().contains("jms/TestDestination"));
			} finally {
				EventBus.unregisterAll();
			}
		}

		@Test
		@DisplayName("the default destination routes an unqualified publish")
		void defaultDestinationResolves() {
			EventBus.unregisterAll();
			try {
				EventPublisher publisher = new EventPublisher(EventBus.CONNECTION_FACTORY_JNDI, "jms/TestDefault");
				EventBus.register(publisher);
				EventBus.setDefaultDestinationJndi("jms/TestDefault");

				assertSame(publisher, EventBus.publisherFor(null), "null destination means the default");
				assertTrue(EventBus.isReady());
			} finally {
				EventBus.unregisterAll();
				EventBus.setDefaultDestinationJndi(null);
			}
		}

		@Test
		void unregisterAllResetsTheDefault() {
			EventBus.setDefaultDestinationJndi("jms/Something");
			EventBus.unregisterAll();
			assertEquals(EventBus.TOPIC_JNDI, EventBus.getDefaultDestinationJndi());
		}
	}
}
