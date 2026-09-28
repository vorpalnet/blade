package org.vorpal.blade.framework.v2.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventPublisher;

/// The session a call opens and closes is published once each, whoever asks:
/// the framework's own open on the first INVITE, a callflow's explicit stop,
/// and the backstop at invalidation all meet at the same two events.
class AnalyticsSessionTest {

	/// Records what would have gone to the topic.
	static final class Capture extends EventPublisher {
		final List<CloudEvent> sent = new ArrayList<>();

		Capture() {
			super(EventBus.CONNECTION_FACTORY_JNDI, EventBus.TOPIC_JNDI);
		}

		@Override
		public void publish(CloudEvent event) {
			sent.add(event);
		}

		@Override
		public void close() {
		}
	}

	private Capture bus;
	private DetachedApplicationSession app;
	private DetachedRequest invite;

	@BeforeEach
	void setUp() throws Exception {
		bus = new Capture();
		EventBus.register(bus);
		app = new DetachedApplicationSession("test");
		app.setAttribute("VORPAL_SESSION", "0000ABCD");
		app.setAttribute("VORPAL_TIMESTAMP", Long.toHexString(1_787_780_167_411L).toUpperCase());
		invite = new DetachedRequest(app, "INVITE", "sip:alice@example.com", "sip:bob@example.com");
	}

	@AfterEach
	void tearDown() {
		EventBus.unregisterAll();
	}

	private long count(String type) {
		return bus.sent.stream().filter(e -> type.equals(e.getType())).count();
	}

	@Test
	void openedOnceClosedOnce() {
		Analytics.sessionStart(invite);
		Analytics.sessionStart(invite);
		Analytics.sessionStop(invite);
		Analytics.sessionClose(app);
		Analytics.sessionStop(invite);

		assertEquals(1, count(BladeEventTypes.SESSION_STARTED));
		assertEquals(1, count(BladeEventTypes.SESSION_STOPPED));
	}

	@Test
	void theBackstopClosesWhatNoCallflowDid() {
		Analytics.sessionStart(invite);
		Analytics.sessionClose(app);

		assertEquals(1, count(BladeEventTypes.SESSION_STOPPED));
		CloudEvent start = bus.sent.get(0);
		CloudEvent stop = bus.sent.get(1);
		assertEquals(start.getData().path("startedAt").asText(), stop.getData().path("startedAt").asText(),
				"the stop names the row the start opened");
		assertTrue(stop.getData().hasNonNull("stoppedAt"));
	}

	@Test
	void aClosedSessionIsNotReopened() {
		Analytics.sessionStart(invite);
		Analytics.sessionStop(invite);
		Analytics.sessionStart(invite);

		assertEquals(1, count(BladeEventTypes.SESSION_STARTED));
	}

	@Test
	void aCallWithNoVorpalIdPublishesNothing() throws Exception {
		DetachedApplicationSession bare = new DetachedApplicationSession("test");
		Analytics.sessionStart(new DetachedRequest(bare, "INVITE", "sip:a@example.com", "sip:b@example.com"));
		Analytics.sessionClose(bare);

		assertTrue(bus.sent.isEmpty());
		assertNull(bare.getAttribute(Analytics.SESSION_STATE));
	}
}
