package org.vorpal.blade.framework.v3.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventPublisher;

/// Where a call came from: the address its INVITE came from and its caller's
/// area, noted once and published after the session opens, never the number.
class CallOriginTest {

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

	@Test
	void theAreaAndOriginArePublishedOnceAsCallKeys() throws Exception {
		DetachedRequest call = new DetachedRequest(app, "INVITE", "sip:+16465550100@carrier.example",
				"sip:bob@example.com");
		CallOrigin.note(call);
		CallOrigin.publish(app);
		CallOrigin.publish(app);

		assertEquals(2, bus.sent.size(), "the area and the origin, once per call");
		assertEquals(BladeEventTypes.SESSION_KEY, bus.sent.get(0).getType());
		assertEquals(CallOrigin.AREA_KEY, bus.sent.get(0).getData().path("name").asText());
		assertEquals("646", bus.sent.get(0).getData().path("value").asText(), "the area code, never the number");
		assertEquals(CallOrigin.ORIGIN_KEY, bus.sent.get(1).getData().path("name").asText());
		assertEquals("127.0.0.1", bus.sent.get(1).getData().path("value").asText(), "the address the INVITE came from");
	}

	@Test
	void theAssertedIdentityIsTheCaller() throws Exception {
		DetachedRequest call = new DetachedRequest(app, "INVITE", "sip:anonymous@anonymous.invalid",
				"sip:bob@example.com");
		call.setHeader("P-Asserted-Identity", "<sip:+442071234567@carrier.example;user=phone>");
		CallOrigin.note(call);
		assertEquals("+442", app.getAttribute(CallOrigin.CALLER_AREA));
	}

	@Test
	void aVerifiedCallersParametersAreNotTheNumber() throws Exception {
		DetachedRequest call = new DetachedRequest(app, "INVITE", "sip:anonymous@anonymous.invalid",
				"sip:bob@example.com");
		call.setHeader("P-Asserted-Identity", "<sip:+18165550123;verstat=TN-Validation-Passed@carrier.example;user=phone>");
		CallOrigin.note(call);
		assertEquals("816", app.getAttribute(CallOrigin.CALLER_AREA));
	}

	@Test
	void anAssertedIdentityWithNoNumberFallsBackToTheFrom() throws Exception {
		DetachedRequest call = new DetachedRequest(app, "INVITE", "sip:+18165550123@carrier.example",
				"sip:bob@example.com");
		call.setHeader("P-Asserted-Identity", "<sip:trunk7@carrier.example>");
		CallOrigin.note(call);
		assertEquals("816", app.getAttribute(CallOrigin.CALLER_AREA));
	}

	@Test
	void anAreaIsTheAreaCodeOrTheCountryPrefix() {
		assertEquals("646", CallOrigin.area("+16465550100"));
		assertEquals("646", CallOrigin.area("16465550100"));
		assertEquals("646", CallOrigin.area("646-555-0100"));
		assertEquals("646", CallOrigin.area("(646) 555.0100"));
		assertEquals("+442", CallOrigin.area("+442071234567"));
		assertNull(CallOrigin.area("1234"), "an extension");
		assertNull(CallOrigin.area("anonymous"));
		assertNull(CallOrigin.area("0465550100"), "no area code starts 0 or 1");
		assertNull(CallOrigin.area("+4420"), "too short to be a number");
		assertNull(CallOrigin.area(null));
	}
}
