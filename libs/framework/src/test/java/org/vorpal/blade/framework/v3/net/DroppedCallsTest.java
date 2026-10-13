package org.vorpal.blade.framework.v3.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.servlet.sip.SipSession.State;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.net.LegsTest.Capture;

import com.fasterxml.jackson.databind.JsonNode;

/// A call was dropped when it expires with its legs in a state no ordinary ending
/// leaves; a live call and a finished one are not.
class DroppedCallsTest {

	private Capture bus;
	private DetachedApplicationSession app;
	private DetachedSipSession caller, callee;

	@BeforeEach
	void setUp() {
		bus = new Capture();
		EventBus.register(bus);
		Callflow.setSipFactory(new DetachedSipFactory());
		app = new DetachedApplicationSession("test");
		app.setAttribute("VORPAL_SESSION", "0000ABCD");
		app.setAttribute("VORPAL_TIMESTAMP", Long.toHexString(1_787_780_167_411L).toUpperCase());
		caller = new DetachedSipSession(app);
		callee = new DetachedSipSession(app);
		caller.setAttribute(Callflow.LINKED_SESSION, callee.getId());
		callee.setAttribute(Callflow.LINKED_SESSION, caller.getId());
	}

	@AfterEach
	void tearDown() {
		EventBus.unregisterAll();
		Callflow.setSipFactory(null);
	}

	private CloudEvent only() {
		assertEquals(1, bus.sent.size(), "one event");
		CloudEvent e = bus.sent.get(0);
		assertEquals(BladeEventTypes.CALL_DROPPED, e.getType());
		return e;
	}

	private static JsonNode leg(CloudEvent e, String role) {
		for (JsonNode l : e.getData().path("legs")) {
			if (role.equals(l.path("role").asText())) {
				return l;
			}
		}
		throw new AssertionError("no " + role + " leg in " + e.getData());
	}

	@Test
	void aByeNeverRelayedLeavesOneLegUp() {
		caller.setAttribute("userAgent", "caller");
		callee.setAttribute("userAgent", "callee");
		caller.setState(State.TERMINATED);
		callee.setState(State.CONFIRMED);
		DroppedCalls.expiring(app);

		CloudEvent e = only();
		assertEquals("TERMINATED", leg(e, "caller").path("state").asText());
		assertEquals("CONFIRMED", leg(e, "callee").path("state").asText());
		assertTrue(e.getData().path("ageSeconds").asLong() >= 0);
	}

	@Test
	void anAnswerNeverRelayedLeavesTheCallerWaiting() throws Exception {
		caller.setState(State.EARLY);
		caller.setActiveInvite(new DetachedRequest(app, "INVITE", "sip:alice@example.com", "sip:bob@example.net"));
		callee.setState(State.TERMINATED);
		DroppedCalls.expiring(app);

		JsonNode waiting = leg(only(), "caller");
		assertEquals("EARLY", waiting.path("state").asText());
		assertTrue(waiting.path("inviteOpen").asBoolean(), "the caller never got a final response");
	}

	@Test
	void aLegWhosePartnerIsGoneWasDropped() {
		callee.setAttribute("userAgent", "callee");
		callee.setState(State.CONFIRMED);
		caller.invalidate();
		DroppedCalls.expiring(app);

		CloudEvent e = only();
		assertEquals("GONE", leg(e, "gone").path("state").asText());
	}

	@Test
	void aLiveCallWasNotDropped() {
		caller.setState(State.CONFIRMED);
		callee.setState(State.CONFIRMED);
		DroppedCalls.expiring(app);
		assertTrue(bus.sent.isEmpty(), "the keep-alive probe decides a live call");
	}

	@Test
	void aFinishedCallWasNotDropped() {
		caller.setState(State.TERMINATED);
		callee.setState(State.TERMINATED);
		DroppedCalls.expiring(app);
		assertTrue(bus.sent.isEmpty(), "ended, just never invalidated");
	}

	@Test
	void aSessionWithNoLinkedLegsIsNotACall() {
		DetachedApplicationSession proxy = new DetachedApplicationSession("proxy");
		new DetachedSipSession(proxy).setState(State.EARLY);
		DroppedCalls.expiring(proxy);
		assertTrue(bus.sent.isEmpty());
	}

	@Test
	void aCallIsReportedOnce() {
		caller.setState(State.TERMINATED);
		callee.setState(State.CONFIRMED);
		DroppedCalls.expiring(app);
		DroppedCalls.expiring(app);
		assertEquals(1, bus.sent.size(), "the probe may extend it and it expires again");
	}
}
