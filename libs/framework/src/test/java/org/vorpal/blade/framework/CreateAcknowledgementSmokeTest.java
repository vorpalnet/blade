package org.vorpal.blade.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.UAMode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v2.testing.DummyApplicationSession;
import org.vorpal.blade.framework.v2.testing.DummyRequest;
import org.vorpal.blade.framework.v2.testing.DummySipFactory;
import org.vorpal.blade.framework.v2.testing.DummySipSession;

/// Covers [Callflow#createAcknowledgement(SipServletResponse,SipServletRequest)]
/// — that it matches whichever acknowledgement arrived upstream, and refuses
/// anything else.
///
/// Also pins the CANCEL route the acknowledgement API deliberately does not
/// cover: a CANCEL comes from `createCancel()` on the outstanding INVITE, which
/// is the shape `Terminate` and `ReferTransfer` use.
class CreateAcknowledgementSmokeTest {

	@BeforeAll
	static void installContainerStandIns() {
		Callflow.setSipFactory(new DummySipFactory());
		Callflow.setSipLogger(new CapturingLogger());
	}

	@AfterAll
	static void removeContainerStandIns() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
	}

	private static DummyRequest inbound(DummyApplicationSession appSession, String method) throws Exception {
		DummyRequest request = new DummyRequest(appSession, method);
		request.setSession(new DummySipSession(appSession));
		return request;
	}

	@Test
	void anAckUpstreamProducesAnAckDownstream() throws Exception {
		DummyApplicationSession appSession = new DummyApplicationSession("test");
		DummyRequest aliceAck = inbound(appSession, "ACK");
		aliceAck.setHeader("X-Custom", "carried-across");
		SipServletResponse bobResponse = inbound(appSession, "INVITE").createResponse(200, "OK");

		SipServletRequest bobAck = Callflow.createAcknowledgement(bobResponse, aliceAck);

		assertNotNull(bobAck);
		assertEquals("ACK", bobAck.getMethod());
		assertEquals("carried-across", bobAck.getHeader("X-Custom"), "non-system headers should copy");
	}

	@Test
	void aPrackUpstreamProducesAPrackDownstream() throws Exception {
		DummyApplicationSession appSession = new DummyApplicationSession("test");
		DummyRequest alicePrack = inbound(appSession, "PRACK");
		SipServletResponse bobResponse = inbound(appSession, "INVITE").createResponse(183, "Session Progress");

		SipServletRequest bobPrack = Callflow.createAcknowledgement(bobResponse, alicePrack);

		assertEquals("PRACK", bobPrack.getMethod());
	}

	/// Anything that is not an acknowledgement is refused rather than quietly
	/// producing the wrong message.
	@Test
	void anythingElseIsRefused() throws Exception {
		DummyApplicationSession appSession = new DummyApplicationSession("test");
		DummyRequest aliceInfo = inbound(appSession, "INFO");
		SipServletResponse bobResponse = inbound(appSession, "INVITE").createResponse(200, "OK");

		ServletParseException ex = assertThrows(ServletParseException.class,
				() -> Callflow.createAcknowledgement(bobResponse, aliceInfo));
		assertTrue(ex.getMessage().contains("INFO"), ex.getMessage());
	}

	/// A CANCEL is not an acknowledgement and cannot be built by either factory
	/// route — it comes from the INVITE it cancels. This is the pattern
	/// `Terminate` uses and the one `ReferTransfer` was fixed to use.
	@Test
	void cancelComesFromTheOutstandingInvite() throws Exception {
		DummyApplicationSession appSession = new DummyApplicationSession("test");
		DummyRequest bobInvite = inbound(appSession, "INVITE");
		DummySipSession bobSession = (DummySipSession) bobInvite.getSession();
		bobSession.setActiveInvite(bobInvite);

		SipServletRequest outstanding = bobSession.getActiveInvite(UAMode.UAC);
		assertSame(bobInvite, outstanding);

		SipServletRequest cancel = outstanding.createCancel();
		assertEquals("CANCEL", cancel.getMethod());
		assertSame(bobSession, cancel.getSession());
	}

	/// The container refuses to build a CANCEL from a session, which is what made
	/// the old ReferTransfer line throw on every abandoned transfer. The mock
	/// mirrors that so the mistake cannot come back unnoticed.
	@Test
	void sessionsRefuseToBuildAcksAndCancels() throws Exception {
		DummyApplicationSession appSession = new DummyApplicationSession("test");
		DummySipSession session = new DummySipSession(appSession);

		assertThrows(IllegalArgumentException.class, () -> session.createRequest("CANCEL"));
		assertThrows(IllegalArgumentException.class, () -> session.createRequest("ACK"));
	}
}
