package org.vorpal.blade.framework.v3.diagnostics;

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
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventPublisher;

/// Every call is recorded from its first INVITE; only a call that went wrong
/// is written out, and every call is let go when it ends.
class FlightRecorderTest {

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
	private DetachedSipSession caller, callee;

	@BeforeEach
	void setUp() {
		bus = new Capture();
		EventBus.register(bus);
		FlightRecorder.clear();
		Callflow.setSipFactory(new DetachedSipFactory());
		app = new DetachedApplicationSession("test");
		app.setAttribute("VORPAL_SESSION", "0000ABCD");
		app.setAttribute("VORPAL_TIMESTAMP", Long.toHexString(1_787_780_167_411L).toUpperCase());
		caller = new DetachedSipSession(app);
		callee = new DetachedSipSession(app);
	}

	@AfterEach
	void tearDown() {
		EventBus.unregisterAll();
		Callflow.setSipFactory(null);
		FlightRecorder.clear();
	}

	private DetachedRequest invite(DetachedSipSession leg, String to) throws Exception {
		DetachedRequest r = new DetachedRequest(app, "INVITE", "sip:+15550100@example.com", to);
		r.setRequestURI(Callflow.getSipFactory().createURI(to));
		r.setSession(leg);
		r.setHeader("CSeq", "1 INVITE");
		return r;
	}

	@Test
	void aRefusedCallIsWrittenOutWithItsVerdict() throws Exception {
		DetachedRequest in = invite(caller, "sip:+15550199@blade.example.net");
		FlightRecorder.record(false, in, null);
		DetachedRequest out = invite(callee, "sip:+15550199@pbx.example.net:5070");
		FlightRecorder.record(true, out, null);
		FlightRecorder.record(false, null, new DetachedResponse(out, 503));
		FlightRecorder.record(true, null, new DetachedResponse(in, 503));
		assertEquals(1, FlightRecorder.size());

		FlightRecorder.finish(app, false);
		assertEquals(0, FlightRecorder.size(), "let go");
		assertEquals(1, bus.sent.size());
		CloudEvent e = bus.sent.get(0);
		assertEquals(BladeEventTypes.CALL_TRACED, e.getType());
		assertEquals("downstream", e.getData().path("fault").asText());
		assertEquals("error", e.getData().path("finding").asText());
		assertEquals("pbx.example.net:5070", e.getData().path("peer").asText(), "host and port, never the number");
		assertEquals(4, e.getData().path("steps").size());
		assertTrue(!e.getData().toString().contains("15550199"), "no phone number anywhere: " + e.getData());
	}

	@Test
	void aCallThatEndsWellIsLetGoSilently() throws Exception {
		DetachedRequest in = invite(caller, "sip:+15550199@blade.example.net");
		FlightRecorder.record(false, in, null);
		DetachedRequest out = invite(callee, "sip:+15550199@pbx.example.net");
		FlightRecorder.record(true, out, null);
		FlightRecorder.record(false, null, new DetachedResponse(out, 486));
		FlightRecorder.record(true, null, new DetachedResponse(in, 486));
		FlightRecorder.finish(app, false);
		assertTrue(bus.sent.isEmpty(), "a callee declining is no fault");
		assertEquals(0, FlightRecorder.size());
	}

	@Test
	void aDroppedCallIsWrittenOutWhateverItsVerdict() throws Exception {
		FlightRecorder.record(false, invite(caller, "sip:+15550199@blade.example.net"), null);
		FlightRecorder.finish(app, true);
		assertEquals(1, bus.sent.size());
		assertTrue(bus.sent.get(0).getData().path("dropped").asBoolean());
	}

	@Test
	void onlyAnInitialInviteStartsALadder() throws Exception {
		DetachedRequest options = new DetachedRequest(app, "OPTIONS", "sip:a@example.com", "sip:b@example.com");
		options.setSession(caller);
		FlightRecorder.record(false, options, null);
		DetachedRequest bye = invite(caller, "sip:b@example.com");
		bye.setInitial(false);
		FlightRecorder.record(false, bye, null);
		assertEquals(0, FlightRecorder.size());
	}
}
