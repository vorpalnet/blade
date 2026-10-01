package org.vorpal.blade.framework.v2.b2bua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedResponse;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.sip.DetachedSipSessionsUtil;
import org.vorpal.blade.framework.sip.DetachedSipURI;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.Callflow;

/// A caller who hangs up while `callStarted` is still working: a listener that
/// waits on a web service holds that window open for seconds.
///
/// The container answers the caller's CANCEL itself, so all [InitialInvite]
/// can do is make sure the callee does not keep ringing. RFC 3261 section 9.1
/// bars a CANCEL before the callee's first response, which is why the CANCEL
/// that [Terminate] could not send is sent here, on the first provisional.
@DisplayName("a caller who cancels while callStarted is working")
class CancelDuringCallStartedTest {

	/// Every request that reaches `send()`, by method, in order.
	static final List<String> wire = new ArrayList<>();

	/// A request whose `send()` is recorded and whose CANCEL is too.
	static class RecordingRequest extends DetachedRequest {
		private static final long serialVersionUID = 1L;
		boolean committed;

		RecordingRequest(SipApplicationSession appSession, String method) throws ServletParseException {
			super(appSession, method);
		}

		@Override
		public boolean isCommitted() {
			return committed;
		}

		@Override
		public void send() {
			wire.add(getMethod());
		}

		@Override
		public SipServletRequest createCancel() {
			try {
				RecordingRequest cancel = new RecordingRequest(getApplicationSession(), "CANCEL");
				cancel.setSession(getSession());
				cancel.setInitial(false);
				return cancel;
			} catch (ServletParseException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/// The callee's dialog: its BYE is recorded.
	static class RecordingSession extends DetachedSipSession {
		RecordingSession(SipApplicationSession appSession) {
			super(appSession);
		}

		@Override
		public SipServletRequest createRequest(String method) {
			try {
				RecordingRequest request = new RecordingRequest(getApplicationSession(), method);
				request.setSession(this);
				return request;
			} catch (ServletParseException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/// A callee response whose ACK is recorded.
	static class RecordingResponse extends DetachedResponse {
		private static final long serialVersionUID = 1L;

		RecordingResponse(SipServletRequest request, int status) {
			super(request, status);
		}

		@Override
		public SipServletRequest createAck() {
			try {
				RecordingRequest ack = new RecordingRequest(getApplicationSession(), "ACK");
				ack.setSession(getSession());
				return ack;
			} catch (ServletParseException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/// Builds the callee's INVITE as a recording request on a recording session.
	static class RecordingFactory extends DetachedSipFactory {
		@Override
		public SipServletRequest createRequest(SipApplicationSession appSession, String method, Address from,
				Address to) {
			try {
				RecordingRequest request = new RecordingRequest(appSession, method);
				if (from != null) {
					request.setHeader("From", from.toString());
				}
				if (to != null) {
					request.setHeader("To", to.toString());
					request.setRequestURI(to.getURI());
				}
				request.setSession(new RecordingSession(appSession));
				return request;
			} catch (ServletParseException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/// A listener whose `callStarted` runs the given step, standing in for the
	/// web-service lookup during which the caller hangs up.
	static class SlowListener implements B2buaListener {
		private static final long serialVersionUID = 1L;
		transient Runnable duringCallStarted = () -> {
		};
		SipServletRequest outbound;

		@Override
		public void callStarted(SipServletRequest outboundRequest) {
			outbound = outboundRequest;
			duringCallStarted.run();
		}

		@Override
		public void callAnswered(SipServletResponse outboundResponse) {
		}

		@Override
		public void callConnected(SipServletRequest outboundRequest) {
		}

		@Override
		public void callCompleted(SipServletRequest outboundRequest) {
		}

		@Override
		public void callDeclined(SipServletResponse outboundResponse) {
		}

		@Override
		public void callAbandoned(SipServletRequest outboundRequest) {
		}

		@Override
		public void requestEvent(SipServletRequest bobRequest) {
		}

		@Override
		public void responseEvent(SipServletResponse aliceResponse) {
		}
	}

	private RecordingRequest alice;
	private SlowListener listener;

	@BeforeEach
	void setUp() throws Exception {
		Callflow.setSipFactory(new RecordingFactory());
		Callflow.setSipLogger(new CapturingLogger());
		Callflow.setSipUtil(new DetachedSipSessionsUtil());
		wire.clear();

		DetachedApplicationSession appSession = new DetachedApplicationSession("cancel");
		alice = new RecordingRequest(appSession, "INVITE");
		alice.setSession(new DetachedSipSession(appSession));
		alice.setHeader("From", "<sip:alice@example.com>;tag=a1");
		alice.setHeader("To", "<sip:bob@example.com>");
		alice.setRequestURI(new DetachedSipURI("sip:bob@example.com"));
		listener = new SlowListener();
	}

	@AfterEach
	void tearDown() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
		Callflow.setSipUtil(null);
	}

	/// The container's 487 to the caller: what `isCommitted()` reports once the
	/// CANCEL has been answered.
	private void callerCancels() {
		alice.committed = true;
	}

	/// Hands a callee response to the callflow the way the container would.
	private static void deliver(SipServletResponse response) throws Exception {
		Callback<SipServletResponse> callback = Callflow.pullCallback(response);
		assertNotNull(callback, "no callback was registered for this response");
		callback.acceptThrows(response);
	}

	private RecordingResponse calleeSays(int status) {
		return new RecordingResponse(listener.outbound, status);
	}

	@Test
	void doesNotCallTheCalleeWhenTheCallerIsAlreadyGone() throws Exception {
		listener.duringCallStarted = this::callerCancels;

		new InitialInvite(listener).process(alice);

		assertEquals(List.of(), wire, "no INVITE to a callee nobody is waiting for");
	}

	@Test
	void cancelsTheCalleeOnItsFirstProvisional() throws Exception {
		new InitialInvite(listener).process(alice);
		assertEquals(List.of("INVITE"), wire);

		// The caller hangs up before the callee has said anything, so Terminate's
		// CANCEL was not allowed out.
		callerCancels();
		deliver(calleeSays(180));

		assertEquals(List.of("INVITE", "CANCEL"), wire);
		assertTrue(Boolean.TRUE.equals(listener.outbound.getSession().getAttribute(Callflow.ATTR_CANCEL_SENT)));
	}

	@Test
	void cancelsOnlyOnceAcrossSeveralProvisionals() throws Exception {
		new InitialInvite(listener).process(alice);
		callerCancels();

		deliver(calleeSays(100));
		deliver(calleeSays(180));
		deliver(calleeSays(183));

		assertEquals(List.of("INVITE", "CANCEL"), wire);
	}

	@Test
	void leavesItToTerminateWhenTerminateAlreadyCancelled() throws Exception {
		new InitialInvite(listener).process(alice);
		callerCancels();
		listener.outbound.getSession().setAttribute(Callflow.ATTR_CANCEL_SENT, true);

		deliver(calleeSays(180));

		assertEquals(List.of("INVITE"), wire, "Terminate's CANCEL was the one");
	}

	@Test
	void hangsUpACalleeThatAnswersAsTheCancelCrosses() throws Exception {
		new InitialInvite(listener).process(alice);
		callerCancels();

		deliver(calleeSays(200));

		assertEquals(List.of("INVITE", "ACK", "BYE"), wire);
	}

	@Test
	void sendsNothingForACalleeThatRefuses() throws Exception {
		new InitialInvite(listener).process(alice);
		callerCancels();

		deliver(calleeSays(487));

		assertEquals(List.of("INVITE"), wire, "the container acknowledges a failure itself");
	}

	@Test
	void relaysAProvisionalWhenNobodyCancels() throws Exception {
		new InitialInvite(listener).process(alice);

		deliver(calleeSays(180));

		assertEquals(List.of("INVITE"), wire, "the provisional is relayed upstream, nothing sent downstream");
	}
}
