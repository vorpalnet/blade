package org.vorpal.blade.framework.v3.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/// The verdict a call's messages support, for the ways calls go wrong and the
/// ways they end well. Leg 1 is the caller's, leg 2 the callee's.
class CallLadderTest {

	private static final String CALLER = "198.51.100.7:5060", CALLEE = "pbx.example.net", CALLEE_AT = "198.51.100.20:5060";

	private final CallLadder ladder = new CallLadder();
	private long now = 1_000;

	private void in(int leg, String method, int status, long cseq, String peer) {
		ladder.add(new CallLadder.Step(now++, false, leg, method, status, cseq, peer));
	}

	private void out(int leg, String method, int status, long cseq, String peer) {
		ladder.add(new CallLadder.Step(now++, true, leg, method, status, cseq, peer));
	}

	/// The caller's INVITE, relayed to the callee.
	private void setUp() {
		in(1, "INVITE", 0, 1, CALLER);
		out(2, "INVITE", 0, 1, CALLEE);
	}

	private void answered() {
		setUp();
		in(2, "INVITE", 200, 1, CALLEE_AT);
		out(1, "INVITE", 200, 1, CALLER);
		in(1, "ACK", 0, 1, CALLER);
		out(2, "ACK", 0, 1, CALLEE);
	}

	@Test
	void aCallThatEndsWellLeavesNoVerdict() {
		answered();
		in(1, "BYE", 0, 2, CALLER);
		out(2, "BYE", 0, 2, CALLEE);
		in(2, "BYE", 200, 2, CALLEE_AT);
		out(1, "BYE", 200, 2, CALLER);
		assertNull(ladder.verdict());
	}

	@Test
	void aCalleeDecliningIsNoFault() {
		setUp();
		in(2, "INVITE", 486, 1, CALLEE_AT);
		out(1, "INVITE", 486, 1, CALLER);
		assertNull(ladder.verdict());
	}

	@Test
	void aServerThatNeverAnsweredIsNamed() {
		setUp();
		in(2, "INVITE", 408, 1, null); // the container's own: nothing came back
		out(1, "INVITE", 408, 1, CALLER);
		CallLadder.Verdict v = ladder.verdict();
		assertEquals("downstream", v.fault);
		assertEquals("timeout", v.finding);
		assertEquals(CALLEE, v.peer, "where the INVITE was sent, not the container that answered");
	}

	@Test
	void aRefusalNeverPassedOnIsTheApplications() {
		setUp();
		in(2, "INVITE", 486, 1, CALLEE_AT);
		// ...and the caller hears nothing: the listener's bug of 2026-10-12.
		CallLadder.Verdict v = ladder.verdict();
		assertEquals("application", v.fault);
		assertEquals("unanswered", v.finding);
		assertEquals("INVITE", v.step.method);
		assertEquals(CALLER, v.peer);
	}

	@Test
	void aCallerWhoGaveUpWaitingOnALostAnswerIsNoAbandon() {
		setUp();
		in(2, "INVITE", 486, 1, CALLEE_AT);
		in(1, "CANCEL", 0, 1, CALLER); // the caller heard nothing, and hung up
		CallLadder.Verdict v = ladder.verdict();
		assertEquals("unanswered", v.finding, "the callee had answered; the caller never heard it");
	}

	@Test
	void aCallerWhoHungUpWhileABackupRangIsAnAbandon() {
		setUp();
		in(2, "INVITE", 503, 1, CALLEE_AT);
		out(3, "INVITE", 0, 1, "backup.example.net");
		in(1, "CANCEL", 0, 1, CALLER);
		out(3, "CANCEL", 0, 1, "backup.example.net");
		in(3, "INVITE", 487, 1, "198.51.100.21:5060");
		assertEquals("error", ladder.verdict().finding,
				"the caller simply gave up; what is left is the first server's 503, on a call never answered");
	}

	@Test
	void aByeNeverPassedOnIsTheApplications() {
		answered();
		in(1, "BYE", 0, 2, CALLER);
		out(1, "BYE", 200, 2, CALLER);
		CallLadder.Verdict v = ladder.verdict();
		assertEquals("application", v.fault);
		assertEquals("unrelayed", v.finding);
	}

	@Test
	void aServerThatForgotTheCallIsNamed() {
		answered();
		in(1, "BYE", 0, 2, CALLER);
		out(2, "BYE", 0, 2, CALLEE);
		in(2, "BYE", 481, 2, CALLEE_AT);
		out(1, "BYE", 200, 2, CALLER);
		CallLadder.Verdict v = ladder.verdict();
		assertEquals("forgot", v.finding);
		assertEquals(481, v.step.status);
	}

	@Test
	void aRequestNeverAnsweredAtAllIsTheServers() {
		answered();
		out(2, "BYE", 0, 2, CALLEE);
		CallLadder.Verdict v = ladder.verdict();
		assertEquals("downstream", v.fault);
		assertEquals("silent", v.finding);
		assertEquals(CALLEE, v.peer);
	}

	@Test
	void aCancelledCallIsNoFault() {
		setUp();
		in(1, "CANCEL", 0, 1, CALLER); // the container answers it, and the INVITE with 487
		out(2, "CANCEL", 0, 1, CALLEE);
		in(2, "CANCEL", 200, 1, CALLEE_AT);
		in(2, "INVITE", 487, 1, CALLEE_AT);
		assertNull(ladder.verdict());
	}

	@Test
	void aRefusedLegTheCallRecoveredFromIsNoFault() {
		setUp();
		in(2, "INVITE", 503, 1, CALLEE_AT);
		out(3, "INVITE", 0, 1, "backup.example.net");
		in(3, "INVITE", 200, 1, "198.51.100.21:5060");
		out(1, "INVITE", 200, 1, CALLER);
		assertNull(ladder.verdict());
	}

	@Test
	void aRefusalTheCallDidNotRecoverFromIsTheServers() {
		setUp();
		in(2, "INVITE", 503, 1, CALLEE_AT);
		out(1, "INVITE", 503, 1, CALLER);
		assertEquals("error", ladder.verdict().finding);
	}

	@Test
	void aLongCallKeepsItsEndsAndItsVerdict() {
		answered();
		for (int i = 0; i < 50; i++) {
			out(2, "UPDATE", 0, 10 + i, CALLEE);
			in(2, "UPDATE", 200, 10 + i, CALLEE_AT);
		}
		out(2, "BYE", 0, 99, CALLEE);
		assertEquals(CallLadder.HEAD + CallLadder.TAIL, ladder.steps().size());
		assertEquals(6 + 100 + 1 - CallLadder.HEAD - CallLadder.TAIL, ladder.omitted());
		assertEquals("silent", ladder.verdict().finding, "the last BYE, unanswered, despite the middle left out");
	}
}
