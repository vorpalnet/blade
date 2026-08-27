package org.vorpal.blade.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;

/// Pins the contract of [Callflow#createResponse(javax.servlet.sip.SipServletRequest,SipServletResponse)]:
/// it answers the upstream request, and it never returns null. It used to return
/// null when the upstream dialog had gone away, which surfaced as a
/// NullPointerException wherever the result was first dereferenced; it now
/// throws `ServletParseException` with a message naming the cause, matching how
/// `v3.Callflow.createRequest` reports its failures.
class CreateResponseSmokeTest {

	@BeforeAll
	static void installContainerStandIns() {
		Callflow.setSipFactory(new DetachedSipFactory());
		Callflow.setSipLogger(new CapturingLogger());
	}

	@AfterAll
	static void removeContainerStandIns() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
	}

	private static DetachedRequest inbound(DetachedApplicationSession appSession, String method) throws Exception {
		DetachedRequest request = new DetachedRequest(appSession, method);
		request.setSession(new DetachedSipSession(appSession));
		return request;
	}

	@Nested
	@DisplayName("the ordinary case")
	class Answers {

		@Test
		void copiesStatusAndReasonPhrase() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");
			DetachedRequest aliceRequest = inbound(appSession, "INVITE");
			DetachedRequest bobRequest = inbound(appSession, "INVITE");
			SipServletResponse bobResponse = bobRequest.createResponse(486, "Busy Here");

			SipServletResponse aliceResponse = Callflow.createResponse(aliceRequest, bobResponse);

			assertNotNull(aliceResponse);
			assertEquals(486, aliceResponse.getStatus());
			assertEquals("Busy Here", aliceResponse.getReasonPhrase());
		}

		@Test
		void copiesNonSystemHeaders() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");
			DetachedRequest aliceRequest = inbound(appSession, "INVITE");
			DetachedRequest bobRequest = inbound(appSession, "INVITE");
			SipServletResponse bobResponse = bobRequest.createResponse(200, "OK");
			bobResponse.setHeader("X-Custom", "carried-across");

			SipServletResponse aliceResponse = Callflow.createResponse(aliceRequest, bobResponse);

			assertEquals("carried-across", aliceResponse.getHeader("X-Custom"));
		}
	}

	@Nested
	@DisplayName("a coding mistake")
	class BadArguments {

		@Test
		void nullRequestIsRejected() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");
			SipServletResponse bobResponse = inbound(appSession, "INVITE").createResponse(200);

			ServletParseException ex = assertThrows(ServletParseException.class,
					() -> Callflow.createResponse(null, bobResponse));
			assertTrue(ex.getMessage().contains("aliceRequest"), ex.getMessage());
		}

		@Test
		void nullResponseIsRejected() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");
			DetachedRequest aliceRequest = inbound(appSession, "INVITE");

			ServletParseException ex = assertThrows(ServletParseException.class,
					() -> Callflow.createResponse(aliceRequest, null));
			assertTrue(ex.getMessage().contains("bobResponse"), ex.getMessage());
		}
	}

	@Nested
	@DisplayName("the upstream dialog is gone")
	class DialogGone {

		/// The caller hung up or CANCELed before the downstream response arrived.
		/// A race rather than a bug, but it has to say so rather than hand back a
		/// null that fails somewhere else.
		@Test
		void terminatedSessionIsReported() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");
			DetachedRequest aliceRequest = inbound(appSession, "INVITE");
			DetachedRequest bobRequest = inbound(appSession, "INVITE");
			SipServletResponse bobResponse = bobRequest.createResponse(200, "OK");

			((DetachedSipSession) aliceRequest.getSession()).setState(SipSession.State.TERMINATED);

			ServletParseException ex = assertThrows(ServletParseException.class,
					() -> Callflow.createResponse(aliceRequest, bobResponse));
			assertTrue(ex.getMessage().contains("TERMINATED"), ex.getMessage());
			assertTrue(ex.getMessage().contains("isCommitted"), ex.getMessage());
		}

		@Test
		void invalidatedSessionIsReported() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");
			DetachedRequest aliceRequest = inbound(appSession, "INVITE");
			DetachedRequest bobRequest = inbound(appSession, "INVITE");
			SipServletResponse bobResponse = bobRequest.createResponse(200, "OK");

			aliceRequest.getSession().invalidate();

			assertThrows(ServletParseException.class, () -> Callflow.createResponse(aliceRequest, bobResponse));
		}

		/// The status being relayed appears in the message, so a log line names
		/// what could not be delivered.
		@Test
		void messageNamesTheStatusItCouldNotRelay() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");
			DetachedRequest aliceRequest = inbound(appSession, "INVITE");
			DetachedRequest bobRequest = inbound(appSession, "INVITE");
			SipServletResponse bobResponse = bobRequest.createResponse(200, "OK");

			aliceRequest.getSession().invalidate();

			ServletParseException ex = assertThrows(ServletParseException.class,
					() -> Callflow.createResponse(aliceRequest, bobResponse));
			assertTrue(ex.getMessage().contains("200"), ex.getMessage());
			assertTrue(ex.getMessage().contains("INVITE"), ex.getMessage());
		}
	}
}
