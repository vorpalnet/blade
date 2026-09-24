package org.vorpal.blade.framework.v2.keepalive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.sip.ServletTimer;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.TimerService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.Callflow.GlareState;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedResponse;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.sip.DetachedSipSessionsUtil;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;

/// The keep-alive refresh and the expiration probe, driven through the servlet's
/// real `doResponse` on detached objects: one offerless re-INVITE, chained through
/// the call, with no SDP kept anywhere.
@DisplayName("keep-alive: the chained offerless re-INVITE")
class KeepAliveTest {

	static final String BOB_SDP = "v=0\r\no=bob 1 1 IN IP4 10.0.0.2\r\ns=-\r\nc=IN IP4 10.0.0.2\r\nt=0 0\r\n"
			+ "m=audio 40000 RTP/AVP 0\r\na=sendrecv\r\n";
	static final String ALICE_SDP = "v=0\r\no=alice 7 7 IN IP4 10.0.0.1\r\ns=-\r\nc=IN IP4 10.0.0.1\r\nt=0 0\r\n"
			+ "m=audio 30000 RTP/AVP 0\r\nm=video 30002/2 RTP/AVP 96\r\na=sendrecv\r\n";

	/// One side of the call: records every request the framework creates on it and
	/// every ACK sent for a response it gave.
	class Leg extends DetachedSipSession {
		final String name;
		final List<DetachedRequest> requests = new ArrayList<>();
		final List<DetachedRequest> acks = new ArrayList<>();

		Leg(String name) {
			super(app);
			this.name = name;
			setState(State.CONFIRMED);
		}

		@Override
		public SipServletRequest createRequest(String method) {
			DetachedRequest request = (DetachedRequest) super.createRequest(method);
			request.setInitial(false);
			requests.add(request);
			return request;
		}

		DetachedRequest last(String method) {
			DetachedRequest found = null;
			for (DetachedRequest r : requests) {
				if (r.getMethod().equals(method)) {
					found = r;
				}
			}
			return found;
		}

		long count(String method) {
			return requests.stream().filter(r -> r.getMethod().equals(method)).count();
		}

		/// The endpoint answers the most recent INVITE sent to it.
		void answers(int status, String sdp) throws Exception {
			DetachedRequest invite = last("INVITE");
			DetachedResponse response = new DetachedResponse(invite, status) {
				@Override
				public SipServletRequest createAck() {
					DetachedRequest ack = (DetachedRequest) super.createAck();
					ack.setInitial(false); // an ACK is never an initial request
					acks.add(ack);
					return ack;
				}
			};
			if (sdp != null) {
				response.setContent(sdp, "application/sdp");
			}
			if (status == 481 || status == 408) {
				setState(State.TERMINATED); // the container ends the dialog (RFC 3261 section 12.2.1.2)
			}
			servlet.respond(response);
		}

		String lastAckBody() throws Exception {
			return (String) acks.get(acks.size() - 1).getContent();
		}
	}

	/// A container timer that fires only when the test says so.
	class Timer implements ServletTimer {
		final long delay;
		final Serializable info;

		Timer(long delay, Serializable info) {
			this.delay = delay;
			this.info = info;
		}

		@Override
		public String getId() {
			return "timer-" + timers.indexOf(this);
		}

		@Override
		public SipApplicationSession getApplicationSession() {
			return app;
		}

		@Override
		public Serializable getInfo() {
			return info;
		}

		@Override
		public long scheduledExecutionTime() {
			return 0;
		}

		@Override
		public void cancel() {
		}

		@Override
		public long getTimeRemaining() {
			return delay;
		}

		void fire() {
			servlet.timeout(this);
		}
	}

	final List<Timer> timers = new ArrayList<>();

	final TimerService timerService = new TimerService() {
		@Override
		public ServletTimer createTimer(SipApplicationSession appSession, long delay, boolean isPersistent,
				Serializable info) {
			Timer timer = new Timer(delay, info);
			timers.add(timer);
			return timer;
		}

		@Override
		public ServletTimer createTimer(SipApplicationSession appSession, long delay, long period, boolean fixedDelay,
				boolean isPersistent, Serializable info) {
			return createTimer(appSession, delay, isPersistent, info);
		}
	};

	/// The servlet whose `doResponse` delivers each answer to the chain's callbacks.
	static class Servlet extends AsyncSipServlet {
		private static final long serialVersionUID = 1L;

		void respond(SipServletResponse response) throws Exception {
			doResponse(response);
		}

		@Override
		protected void servletCreated(SipServletContextEvent event) {
		}

		@Override
		protected void servletDestroyed(SipServletContextEvent event) {
		}

		@Override
		protected Callflow chooseCallflow(SipServletRequest request) {
			return null;
		}
	}

	final Servlet servlet = new Servlet();

	DetachedApplicationSession app;
	Leg alice;
	Leg bob;

	@BeforeEach
	void setUp() {
		CapturingLogger log = new CapturingLogger();
		Callflow.setSipFactory(new DetachedSipFactory());
		Callflow.setSipLogger(log);
		AsyncSipServlet.setSipLogger(log);
		Callflow.setSipUtil(new DetachedSipSessionsUtil());
		Callflow.setTimerService(timerService);
		app = new DetachedApplicationSession("keep-alive");
		alice = new Leg("alice");
		bob = new Leg("bob");
		// a B2BUA call: each dialog points at the other
		Callflow.linkSession(alice, bob);
		Callflow.linkSession(bob, alice);
	}

	@AfterEach
	void tearDown() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
		AsyncSipServlet.setSipLogger(null);
		Callflow.setSipUtil(null);
		Callflow.setTimerService(null);
	}

	/// The keep-alive timer fires on Bob's dialog, the one the claiming
	/// application armed.
	private void refresh() {
		new KeepAlive().handle(bob);
	}

	private void assertNoByes() {
		assertEquals(0, alice.count("BYE") + bob.count("BYE"));
	}

	private void assertBothHungUp() {
		assertEquals(1, alice.count("BYE"));
		assertEquals(1, bob.count("BYE"));
	}

	/// The leg that lost the call has no dialog left; only the other is hung up.
	private void assertOnlyHungUp(Leg leg) {
		Leg other = (leg == alice) ? bob : alice;
		assertEquals(1, leg.count("BYE"), leg.name);
		assertEquals(0, other.count("BYE"), other.name + " has no dialog left to hang up");
	}

	// ---- the chain

	@Test
	void oneOfferlessReinviteRefreshesBothDialogs() throws Exception {
		refresh();
		assertEquals(1, bob.count("INVITE"));
		assertNull(bob.last("INVITE").getContent(), "offerless: Bob supplies his own SDP");

		bob.answers(200, BOB_SDP);
		assertEquals(1, alice.count("INVITE"));
		assertEquals(BOB_SDP, alice.last("INVITE").getContent(), "Bob's offer, relayed");

		alice.answers(200, ALICE_SDP);
		assertEquals(ALICE_SDP, bob.lastAckBody(), "Alice's answer, in Bob's ACK");
		assertEquals(1, alice.acks.size());
		assertNull(alice.acks.get(0).getContent());
		assertNoByes();
	}

	@Test
	void noSdpIsKeptOnEitherDialog() throws Exception {
		refresh();
		bob.answers(200, BOB_SDP);
		alice.answers(200, ALICE_SDP);
		for (Leg leg : List.of(alice, bob)) {
			for (String name : leg.getAttributeNameSet()) {
				Object value = leg.getAttribute(name);
				assertFalse(String.valueOf(value).contains("v=0"), leg.name + " keeps SDP in " + name);
			}
		}
	}

	@Test
	void theCallEndingBeforeTheTimerFiresSendsNothing() {
		alice.invalidate();
		refresh();
		assertEquals(0, bob.requests.size());
	}

	// ---- the relay leg, while Bob's 2xx waits for its ACK

	@Test
	void a491OnTheRelayIsRetriedAfterTheRfcDelay() throws Exception {
		refresh();
		bob.answers(200, BOB_SDP);
		alice.answers(491, null);
		assertEquals(1, timers.size());
		long delay = timers.get(0).delay;
		assertTrue(delay >= 2100 && delay <= 4000 && delay % 10 == 0, "RFC 3261 section 14.1: " + delay);
		assertTrue(bob.acks.isEmpty(), "Bob's 2xx waits for the retry");

		timers.get(0).fire();
		assertEquals(2, alice.count("INVITE"));
		assertEquals(BOB_SDP, alice.last("INVITE").getContent());
		alice.answers(200, ALICE_SDP);
		assertEquals(ALICE_SDP, bob.lastAckBody());
		assertNoByes();
	}

	@Test
	void aBusyRelayLegIsRetriedNotSkipped() throws Exception {
		Callflow.setGlareState(alice, GlareState.PROTECT); // a transaction is open on Alice's dialog
		refresh();
		bob.answers(200, BOB_SDP);
		assertEquals(1, timers.size(), "the local 491 is retried like the peer's");

		Callflow.setGlareState(alice, GlareState.ALLOW);
		timers.get(0).fire();
		alice.answers(200, ALICE_SDP);
		assertEquals(ALICE_SDP, bob.lastAckBody());
		assertNoByes();
	}

	@Test
	void aRelayThatStillFailsIsAnsweredWithEveryStreamRejectedAndTheCallEnds() throws Exception {
		refresh();
		bob.answers(200, BOB_SDP);
		for (int i = 0; i < KeepAlive.MAX_RETRIES; i++) {
			alice.answers(491, null);
			timers.get(i).fire();
		}
		alice.answers(491, null);
		assertEquals(KeepAlive.MAX_RETRIES, timers.size());
		assertEquals(KeepAlive.rejectAll(BOB_SDP), bob.lastAckBody(), "RFC 3261 section 13.2.2.4");
		assertBothHungUp();
	}

	@Test
	void aRelayLegThatLostTheCallEndsIt() throws Exception {
		refresh();
		bob.answers(200, BOB_SDP);
		alice.answers(481, null);
		assertEquals(KeepAlive.rejectAll(BOB_SDP), bob.lastAckBody());
		assertOnlyHungUp(bob);
	}

	@Test
	void a422OnTheRelayIsRetriedOnceAtThePeersMinimum() throws Exception {
		refresh();
		bob.answers(200, BOB_SDP);
		DetachedResponse tooSmall = new DetachedResponse(alice.last("INVITE"), 422);
		tooSmall.setHeader("Min-SE", "1800");
		servlet.respond(tooSmall);
		assertEquals(2, alice.count("INVITE"));
		assertEquals("1800", alice.last("INVITE").getHeader("Session-Expires"));
		assertEquals("1800", alice.last("INVITE").getHeader("Min-SE"));
		assertEquals(BOB_SDP, alice.last("INVITE").getContent());
		alice.answers(200, ALICE_SDP);
		assertEquals(ALICE_SDP, bob.lastAckBody());
	}

	// ---- the first leg

	@Test
	void a491OnTheFirstLegRetriesTheWholeChain() throws Exception {
		refresh();
		bob.answers(491, null);
		assertEquals(1, timers.size());
		timers.get(0).fire();
		assertEquals(2, bob.count("INVITE"));
		bob.answers(200, BOB_SDP);
		alice.answers(200, ALICE_SDP);
		assertEquals(ALICE_SDP, bob.lastAckBody());
		assertNoByes();
	}

	@Test
	void aFirstLegThatLostTheCallEndsItWithoutTouchingTheOther() throws Exception {
		refresh();
		bob.answers(481, null);
		assertEquals(0, alice.count("INVITE"));
		assertOnlyHungUp(alice);
	}

	@Test
	void aFirstLegThatDeclinesIsRefreshedFromTheOtherSide() throws Exception {
		refresh();
		bob.answers(488, null); // alive, but will not take an offerless re-INVITE
		assertEquals(1, alice.count("INVITE"));
		assertNull(alice.last("INVITE").getContent());
		alice.answers(200, ALICE_SDP);
		assertEquals(ALICE_SDP, bob.last("INVITE").getContent());
		bob.answers(200, BOB_SDP);
		assertEquals(BOB_SDP, alice.lastAckBody());
		assertNoByes();
	}

	@Test
	void whenBothLegsDeclineTheCallIsLeftToItsSessionTimer() throws Exception {
		refresh();
		bob.answers(488, null);
		alice.answers(488, null);
		assertNoByes();
		assertEquals(1, bob.count("INVITE"));
		assertEquals(1, alice.count("INVITE"));
	}

	// ---- the expiration probe: the same chain, once

	@Test
	void aProbeThatCompletesRestoresTheLifetime() throws Exception {
		app.setExpires(1); // the grace window
		new KeepAlive().probeAndConfirm(bob, 45);
		bob.answers(200, BOB_SDP);
		alice.answers(200, ALICE_SDP);
		assertExpiresInMinutes(45);
		assertNoByes();
	}

	@Test
	void aProbeHangsUpACallAnEndpointHasLost() throws Exception {
		app.setExpires(1);
		new KeepAlive().probeAndConfirm(bob, 45);
		bob.answers(200, BOB_SDP);
		alice.answers(408, null);
		assertOnlyHungUp(bob);
		assertExpiresInMinutes(1);
	}

	@Test
	void aProbeKeepsACallWhoseEndpointsAreAliveButDecline() throws Exception {
		app.setExpires(1);
		new KeepAlive().probeAndConfirm(bob, 45);
		bob.answers(500, null);
		alice.answers(603, null);
		assertExpiresInMinutes(45);
		assertNoByes();
	}

	@Test
	void anExpiredSessionIsProbedFromTheCalledPartysDialog() throws Exception {
		alice.setAttribute("userAgent", "caller"); // what doRequest records on the inbound leg
		servlet.sessionExpired(new SipApplicationSessionEvent(app));
		assertEquals(1, bob.count("INVITE"), "the offerless re-INVITE goes to the called party, as the refresh's does");
		assertNull(bob.last("INVITE").getContent());
		assertEquals(0, alice.count("INVITE"));
		bob.answers(200, BOB_SDP);
		alice.answers(200, ALICE_SDP);
		assertExpiresInMinutes(60); // no lifetime recorded at setup: the configured default
	}

	@Test
	void anApplicationThatAnchorsMediaDeclinesTheProbe() throws Exception {
		DetachedRequest outbound = new DetachedRequest(app, "INVITE");
		outbound.setSession(bob);
		Callflow.declineKeepAlive(outbound);
		assertEquals(Boolean.TRUE, outbound.getAttribute(Callflow.NO_KEEP_ALIVE));

		servlet.sessionExpired(new SipApplicationSessionEvent(app));
		assertEquals(0, alice.requests.size() + bob.requests.size());
	}

	private void assertExpiresInMinutes(int minutes) {
		long remaining = app.getExpirationTime() - System.currentTimeMillis();
		assertTrue(Math.abs(remaining - minutes * 60_000L) < 5_000, remaining + " ms");
	}

	// ---- the rules

	@Test
	void onlyResponsesThatEndTheDialogOrItsUsageMeanTheCallIsLost() {
		List<Integer> lost = List.of(404, 405, 408, 410, 416, 480, 481, 482, 483, 484, 485, 489, 501, 502, 604);
		List<Integer> alive = List.of(400, 403, 415, 422, 486, 487, 488, 491, 500, 503, 600, 603);
		assertEquals(lost, lost.stream().filter(KeepAlive::callLost).collect(Collectors.toList()));
		assertTrue(alive.stream().noneMatch(KeepAlive::callLost), "RFC 5057: transaction only");
	}

	@Test
	void theRejectingAnswerZeroesEveryMediaPort() {
		String answer = KeepAlive.rejectAll(ALICE_SDP);
		assertTrue(answer.contains("\r\nm=audio 0 RTP/AVP 0\r\n"));
		assertTrue(answer.contains("\r\nm=video 0 RTP/AVP 96\r\n"), "a port count goes too");
		assertTrue(answer.contains("c=IN IP4 10.0.0.1"));
		assertEquals(ALICE_SDP.split("\r\n").length, answer.split("\r\n").length);
		assertEquals(KeepAlive.rejectAll(ALICE_SDP), KeepAlive.rejectAll(ALICE_SDP.getBytes()));
	}

	// ---- expiry

	@Test
	void expiryHangsUpBothLegs() {
		new KeepAliveExpiry().handle(bob);
		assertBothHungUp();
	}

	@Test
	void expirySkipsALegTheContainerAlreadyEnded() {
		alice.setState(State.TERMINATED);
		new KeepAliveExpiry().handle(bob);
		assertOnlyHungUp(bob);
	}
}
