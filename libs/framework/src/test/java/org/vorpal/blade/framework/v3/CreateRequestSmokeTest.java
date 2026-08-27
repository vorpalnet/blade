package org.vorpal.blade.framework.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletRequest;

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
import org.vorpal.blade.framework.sip.DetachedSipURI;

/// Exercises [Callflow#createRequest(javax.servlet.sip.URI,SipServletRequest)]
/// without a SIP container, using the mock objects in
/// `org.vorpal.blade.framework.sip`.
///
/// Covers the two paths the method chooses between — in-dialog on the linked dialog
/// versus a fresh initial request from the factory — and the methods it refuses
/// to build at all.
class CreateRequestSmokeTest {

	private static final String LINKED_SESSION = "LINKED_SESSION";

	@BeforeAll
	static void installContainerStandIns() {
		org.vorpal.blade.framework.Callflow.setSipFactory(new DetachedSipFactory());
		// linkSession consults sipLogger before writing the attribute; CapturingLogger
		// is the one Logger that survives without the framework's logging stack.
		org.vorpal.blade.framework.Callflow.setSipLogger(new CapturingLogger());
	}

	@AfterAll
	static void removeContainerStandIns() {
		org.vorpal.blade.framework.Callflow.setSipFactory(null);
		org.vorpal.blade.framework.Callflow.setSipLogger(null);
	}

	/// Builds an inbound request sitting on its own session, the way a container
	/// hands one to a callflow.
	private static DetachedRequest inbound(String method) throws Exception {
		DetachedApplicationSession appSession = new DetachedApplicationSession("test");
		DetachedRequest request = new DetachedRequest(appSession, method);
		request.setSession(new DetachedSipSession(appSession));
		return request;
	}

	@Nested
	@DisplayName("methods that must be derived, not created")
	class Refused {

		@Test
		void ackIsRefused() throws Exception {
			ServletParseException ex = assertThrows(ServletParseException.class,
					() -> Callflow.createRequest(inbound("ACK")));
			assertTrue(ex.getMessage().contains("ACK"), ex.getMessage());
			assertTrue(ex.getMessage().contains("createAcknowledgement"), ex.getMessage());
		}

		@Test
		void cancelIsRefused() throws Exception {
			ServletParseException ex = assertThrows(ServletParseException.class,
					() -> Callflow.createRequest(inbound("CANCEL")));
			assertTrue(ex.getMessage().contains("CANCEL"), ex.getMessage());
			assertTrue(ex.getMessage().contains("createCancel"), ex.getMessage());
		}

		@Test
		void prackIsRefused() throws Exception {
			ServletParseException ex = assertThrows(ServletParseException.class,
					() -> Callflow.createRequest(inbound("PRACK")));
			assertTrue(ex.getMessage().contains("PRACK"), ex.getMessage());
		}

		/// The refusal is on the method, not on its spelling.
		@Test
		void refusalIgnoresCase() throws Exception {
			assertThrows(ServletParseException.class, () -> Callflow.createRequest(inbound("ack")));
		}

		/// A method that has to be derived is refused before anything else is
		/// touched, so no factory and no linked session are needed to trip it.
		@Test
		void refusedBeforeTouchingTheFactory() throws Exception {
			org.vorpal.blade.framework.Callflow.setSipFactory(null);
			try {
				assertThrows(ServletParseException.class, () -> Callflow.createRequest(inbound("CANCEL")));
			} finally {
				org.vorpal.blade.framework.Callflow.setSipFactory(new DetachedSipFactory());
			}
		}
	}

	@Nested
	@DisplayName("dialogs already linked")
	class InDialog {

		/// Links alice's session to bob's the way a relayed response does, then
		/// checks that the outbound request is created on bob's dialog rather than
		/// through the factory.
		@Test
		void createsOnTheLinkedSession() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");

			DetachedSipSession aliceSession = new DetachedSipSession(appSession);
			DetachedSipSession bobSession = new DetachedSipSession(appSession);
			aliceSession.setAttribute(LINKED_SESSION, bobSession.getId());

			DetachedRequest aliceRequest = new DetachedRequest(appSession, "INVITE");
			aliceRequest.setSession(aliceSession);
			aliceRequest.setHeader("X-Custom", "carried-across");

			SipServletRequest bobRequest = Callflow.createRequest(aliceRequest);

			assertNotNull(bobRequest);
			assertSame(bobSession, bobRequest.getSession(), "should be built on the linked dialog");
			assertEquals("INVITE", bobRequest.getMethod());
			assertEquals("carried-across", bobRequest.getHeader("X-Custom"), "non-system headers should copy");
		}

		/// copyContentAndHeaders links for INVITE, so after the call bob's session
		/// points back at alice's. That is the direction the request path writes.
		@Test
		void linksBobBackToAlice() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");

			DetachedSipSession aliceSession = new DetachedSipSession(appSession);
			DetachedSipSession bobSession = new DetachedSipSession(appSession);
			aliceSession.setAttribute(LINKED_SESSION, bobSession.getId());

			DetachedRequest aliceRequest = new DetachedRequest(appSession, "INVITE");
			aliceRequest.setSession(aliceSession);

			Callflow.createRequest(aliceRequest);

			assertEquals(aliceSession.getId(), bobSession.getAttribute(LINKED_SESSION));
		}

		/// A non-INVITE in-dialog request still gets built and copied; it just does
		/// not trigger session linking.
		@Test
		void carriesMidDialogMethods() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");

			DetachedSipSession aliceSession = new DetachedSipSession(appSession);
			DetachedSipSession bobSession = new DetachedSipSession(appSession);
			aliceSession.setAttribute(LINKED_SESSION, bobSession.getId());

			DetachedRequest aliceRequest = new DetachedRequest(appSession, "INFO");
			aliceRequest.setSession(aliceSession);

			SipServletRequest bobRequest = Callflow.createRequest(aliceRequest);

			assertEquals("INFO", bobRequest.getMethod());
			assertSame(bobSession, bobRequest.getSession());
		}
	}

	@Nested
	@DisplayName("no linked dialog yet")
	class Initial {

		/// With nothing linked, the request comes from the factory on a brand-new
		/// session rather than from an existing dialog.
		@Test
		void createsThroughTheFactory() throws Exception {
			DetachedRequest aliceRequest = inbound("INVITE");
			aliceRequest.setHeader("X-Custom", "carried-across");

			SipServletRequest bobRequest = Callflow.createRequest(aliceRequest);

			assertNotNull(bobRequest);
			assertEquals("INVITE", bobRequest.getMethod());
			assertNotSame(aliceRequest.getSession(), bobRequest.getSession(), "should be a new dialog");
			assertEquals("carried-across", bobRequest.getHeader("X-Custom"), "non-system headers should copy");
		}

		/// The retarget case, which is what all sixteen former
		/// `createContinueInitialRequest(uri, req)` call sites do: a bare
		/// destination from config inherits the inbound user part and parameters
		/// through `copyParameters`, rather than going out userless.
		@Test
		void retargetsWhileKeepingTheUserPartAndParameters() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");
			DetachedRequest aliceRequest = new DetachedRequest(appSession, "INVITE");
			aliceRequest.setSession(new DetachedSipSession(appSession));
			aliceRequest.setRequestURI(new DetachedSipURI("sip:alice@caller.example.com;transport=tcp"));

			SipServletRequest bobRequest = Callflow.createRequest(new DetachedSipURI("sip:10.0.0.5:5060"),
					aliceRequest);

			assertEquals("sip:alice@10.0.0.5:5060;transport=tcp", bobRequest.getRequestURI().toString());
		}

		/// A null destination reuses the inbound request URI untouched.
		@Test
		void aNullDestinationKeepsTheInboundRequestUri() throws Exception {
			DetachedApplicationSession appSession = new DetachedApplicationSession("test");
			DetachedRequest aliceRequest = new DetachedRequest(appSession, "INVITE");
			aliceRequest.setSession(new DetachedSipSession(appSession));
			aliceRequest.setRequestURI(new DetachedSipURI("sip:alice@caller.example.com"));

			SipServletRequest bobRequest = Callflow.createRequest(aliceRequest);

			assertEquals("sip:alice@caller.example.com", bobRequest.getRequestURI().toString());
		}

		/// An unlinked dialog is exactly the initial-INVITE case, so this is where the
		/// two sessions first get joined.
		@Test
		void linksTheNewLegBackToTheInbound() throws Exception {
			DetachedRequest aliceRequest = inbound("INVITE");

			SipServletRequest bobRequest = Callflow.createRequest(aliceRequest);

			assertEquals(aliceRequest.getSession().getId(),
					bobRequest.getSession().getAttribute(LINKED_SESSION));
		}
	}
}
