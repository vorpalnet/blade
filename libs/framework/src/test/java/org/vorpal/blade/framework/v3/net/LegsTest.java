package org.vorpal.blade.framework.v3.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedResponse;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventPublisher;

/// Which server refused a leg, and how: once per leg and status, never a
/// provisional, and an INVITE the transport refused is a leg nothing answered.
class LegsTest {

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

	@BeforeEach
	void setUp() {
		bus = new Capture();
		EventBus.register(bus);
		Callflow.setSipFactory(new DetachedSipFactory());
		app = new DetachedApplicationSession("test");
		app.setAttribute("VORPAL_SESSION", "0000ABCD");
		app.setAttribute("VORPAL_TIMESTAMP", Long.toHexString(1_787_780_167_411L).toUpperCase());
	}

	@AfterEach
	void tearDown() {
		EventBus.unregisterAll();
		Callflow.setSipFactory(null);
	}

	private long count() {
		return bus.sent.stream().filter(e -> BladeEventTypes.CALL_BRANCH_FAILED.equals(e.getType())).count();
	}

	@Test
	void aRefusedLegNamesTheServerAndHowOnce() throws Exception {
		DetachedRequest leg = new DetachedRequest(app, "INVITE", "sip:alice@example.com", "sip:bob@pbx.example.net");
		leg.setRequestURI(Callflow.getSipFactory().createURI("sip:bob@pbx.example.net"));
		DetachedResponse refused = new DetachedResponse(leg, 503);
		refused.setHeader("Retry-After", "30");
		Legs.failed(refused);
		Legs.failed(refused);
		Legs.failed(new DetachedResponse(leg, 180));

		assertEquals(1, count(), "once, though delivered twice; never a provisional");
		CloudEvent event = bus.sent.get(0);
		assertEquals(503, event.getData().path("status").asInt());
		assertEquals("sip:bob@pbx.example.net", event.getData().path("destination").asText());
		assertEquals("127.0.0.1", event.getData().path("responder").asText());
		assertEquals(30, event.getData().path("retryAfter").asInt());
	}

	@Test
	void anInviteThatCouldNotBeSentIsALegNothingAnswered() throws Exception {
		DetachedRequest leg = new DetachedRequest(app, "INVITE", "sip:alice@example.com", "sip:bob@pbx.example.net");
		leg.setRequestURI(Callflow.getSipFactory().createURI("sip:bob@pbx.example.net:5999"));
		Legs.notSent(leg, new java.io.IOException("Failed to send request"));
		Legs.notSent(new DetachedRequest(app, "BYE", "sip:alice@example.com", "sip:bob@pbx.example.net"),
				new java.io.IOException("no"));

		assertEquals(1, count(), "an INVITE only");
		CloudEvent event = bus.sent.get(0);
		assertEquals(500, event.getData().path("status").asInt());
		assertEquals("not sent: Failed to send request", event.getData().path("reason").asText());
		assertTrue(event.getData().path("responder").isNull() || event.getData().path("responder").isMissingNode(),
				"nobody answered");
	}
}
