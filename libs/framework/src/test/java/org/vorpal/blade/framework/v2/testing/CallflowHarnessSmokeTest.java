package org.vorpal.blade.framework.v2.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.Callback;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.Callflow;

/// Runs a whole callflow — `process()`, the outbound request, the response
/// callback — with no SIP container, using the doubles in this package.
///
/// This is the template the [testing README](README.md) documents. The one thing
/// a test has to do that production code does not is deliver the response: the
/// container normally matches an inbound response to the session and fires the
/// stored callback, so a test does that step itself with [#deliver].
@DisplayName("running a callflow without a container")
class CallflowHarnessSmokeTest {

	/// A minimal B2BUA leg: forward the inbound INVITE, then answer upstream with
	/// whatever comes back. Roughly what `InitialInvite` does, with nothing else.
	static class ForwardingCallflow extends Callflow {
		private static final long serialVersionUID = 1L;

		SipServletRequest outbound;
		SipServletResponse upstreamAnswer;

		@Override
		public void process(SipServletRequest aliceRequest) throws ServletException, IOException {
			outbound = createRequest(aliceRequest);
			sendRequest(outbound, (bobResponse) -> {
				upstreamAnswer = createResponse(aliceRequest, bobResponse);
				sendResponse(upstreamAnswer);
			});
		}
	}

	@BeforeEach
	void installContainerStandIns() {
		Callflow.setSipFactory(new DummySipFactory());
		Callflow.setSipLogger(new CapturingLogger());
		// Required: sendRequest mints a Vorpal-ID on an initial INVITE, which
		// asks the util whether the id is taken. Without it the NPE becomes a
		// synthetic 500 delivered to your callback.
		Callflow.setSipUtil(new DummySipSessionsUtil());
	}

	@AfterEach
	void removeContainerStandIns() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
		Callflow.setSipUtil(null);
	}

	/// Hands a response to the callflow the way the container would: look up the
	/// callback `sendRequest` stored on the outbound leg and invoke it.
	private static void deliver(SipServletResponse response) throws Exception {
		Callback<SipServletResponse> callback = Callflow.pullCallback(response);
		assertNotNull(callback, "no callback was registered for this response");
		callback.acceptThrows(response);
	}

	private static DummyRequest inboundInvite() throws Exception {
		DummyApplicationSession appSession = new DummyApplicationSession("harness");
		DummyRequest request = new DummyRequest(appSession, "INVITE");
		request.setSession(new DummySipSession(appSession));
		request.setRequestURI(new DummySipURI("sip:bob@example.com"));
		request.setHeader("X-Trace", "abc123");
		request.setContent("v=0\r\no=alice 1 1 IN IP4 10.0.0.1\r\n".getBytes("UTF-8"), "application/sdp");
		return request;
	}

	@Test
	void forwardsTheInviteOnANewLeg() throws Exception {
		DummyRequest alice = inboundInvite();
		ForwardingCallflow callflow = new ForwardingCallflow();

		callflow.process(alice);

		assertNotNull(callflow.outbound, "process() should have built an outbound request");
		assertEquals("INVITE", callflow.outbound.getMethod());
		assertEquals("abc123", callflow.outbound.getHeader("X-Trace"), "non-system headers travel");
		assertNotNull(callflow.outbound.getRawContent(), "the SDP offer travels");
	}

	@Test
	void relaysTheAnswerUpstream() throws Exception {
		DummyRequest alice = inboundInvite();
		ForwardingCallflow callflow = new ForwardingCallflow();
		callflow.process(alice);

		DummyResponse bobAnswer = new DummyResponse((DummyRequest) callflow.outbound, 200, "OK");
		bobAnswer.setHeader("X-Answered-By", "bob");
		deliver(bobAnswer);

		assertNotNull(callflow.upstreamAnswer, "the callback should have built an upstream response");
		assertEquals(200, callflow.upstreamAnswer.getStatus());
		assertEquals("bob", callflow.upstreamAnswer.getHeader("X-Answered-By"));
		assertSame(alice, callflow.upstreamAnswer.getRequest(), "answers alice's own request");
	}

	@Test
	void relaysAFailureUpstreamToo() throws Exception {
		DummyRequest alice = inboundInvite();
		ForwardingCallflow callflow = new ForwardingCallflow();
		callflow.process(alice);

		deliver(new DummyResponse((DummyRequest) callflow.outbound, 486, "Busy Here"));

		assertEquals(486, callflow.upstreamAnswer.getStatus());
		assertTrue(Callflow.failure(callflow.upstreamAnswer), "486 is a failure");
	}
}
