package org.vorpal.blade.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.sip.DetachedApplicationSession;
import org.vorpal.blade.framework.sip.DetachedRequest;
import org.vorpal.blade.framework.sip.DetachedResponse;
import org.vorpal.blade.framework.sip.DetachedSipFactory;
import org.vorpal.blade.framework.sip.DetachedSipSession;
import org.vorpal.blade.framework.sip.DetachedSipSessionsUtil;
import org.vorpal.blade.framework.v2.logging.CapturingLogger;
import org.vorpal.blade.framework.v3.b2bua.Passthru;

/// Two requests of one method open at once on a dialog, relayed by a B2BUA.
///
/// The case is a transfer whose target fails at once: the
/// transfer app sends its 100 Trying NOTIFY and its final NOTIFY back to back,
/// and every B2BUA between it and the transferor relays both before the
/// transferor answers the first. Each relayed request must get its own answer.
/// Keyed by method alone, the second NOTIFY's callback replaced the first's: the
/// answer to the first went to the second, and the first was never answered.
@DisplayName("overlapping requests of one method through a B2BUA")
class OverlappingRequestsTest {

	final AsyncSipServlet servlet = new AsyncSipServlet() {
		private static final long serialVersionUID = 1L;

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
	};

	DetachedApplicationSession app;
	DetachedSipSession alice;
	DetachedSipSession bob;

	/// What the relay sent Bob, in order.
	final List<SipServletRequest> toBob = new ArrayList<>();

	/// The status each of Alice's requests was answered with, by its CSeq.
	final Map<String, Integer> aliceAnswers = new LinkedHashMap<>();

	@BeforeEach
	void setUp() {
		CapturingLogger log = new CapturingLogger();
		Callflow.setSipFactory(new DetachedSipFactory());
		Callflow.setSipLogger(log);
		AsyncSipServlet.setSipLogger(log);
		Callflow.setSipUtil(new DetachedSipSessionsUtil());
		app = new DetachedApplicationSession("overlap");
		alice = new DetachedSipSession(app);
		// Bob's leg numbers its requests when it creates them, as the container does.
		bob = new DetachedSipSession(app) {
			long cseq = 1;

			@Override
			public SipServletRequest createRequest(String method) {
				SipServletRequest request = super.createRequest(method);
				request.setHeader("CSeq", (cseq++) + " " + method);
				toBob.add(request);
				return request;
			}
		};
		Callflow.linkSession(bob, alice);
	}

	@AfterEach
	void tearDown() {
		Callflow.setSipFactory(null);
		Callflow.setSipLogger(null);
		AsyncSipServlet.setSipLogger(null);
		Callflow.setSipUtil(null);
	}

	/// An in-dialog request from Alice, recording the status it is answered with.
	private DetachedRequest fromAlice(String method, int cseq) throws Exception {
		String key = cseq + " " + method;
		DetachedRequest request = new DetachedRequest(app, method) {
			private static final long serialVersionUID = 1L;

			@Override
			public SipServletResponse createResponse(int status) {
				aliceAnswers.put(key, status);
				return super.createResponse(status);
			}
		};
		request.setSession(alice);
		request.setInitial(false);
		request.setHeader("CSeq", key);
		return request;
	}

	/// Bob answers one of the requests the relay sent him.
	private void bobAnswers(int index, int status) throws Exception {
		SipServletRequest request = toBob.get(index);
		servlet.doResponse(new DetachedResponse(request, status));
	}

	@Test
	void eachRelayedNotifyGetsItsOwnAnswer() throws Exception {
		new Passthru(null).process(fromAlice("NOTIFY", 2)); // 100 Trying
		new Passthru(null).process(fromAlice("NOTIFY", 3)); // final, before Bob answered the first
		assertEquals(2, toBob.size());

		bobAnswers(0, 200);
		bobAnswers(1, 481); // a different status, so a crossed answer shows

		assertEquals(Map.of("2 NOTIFY", 200, "3 NOTIFY", 481), aliceAnswers);
	}

	@Test
	void answersInEitherOrderReachTheRightRequest() throws Exception {
		new Passthru(null).process(fromAlice("NOTIFY", 2));
		new Passthru(null).process(fromAlice("NOTIFY", 3));

		bobAnswers(1, 481);
		bobAnswers(0, 200);

		assertEquals(Map.of("2 NOTIFY", 200, "3 NOTIFY", 481), aliceAnswers);
	}

	@Test
	void aTimeoutAnswersItsOwnRequestAndLeavesNoCallback() throws Exception {
		new Passthru(null).process(fromAlice("NOTIFY", 2));
		new Passthru(null).process(fromAlice("NOTIFY", 3));

		bobAnswers(1, 200);
		bobAnswers(0, 408); // the container's Timer F answer carries the request's CSeq

		assertEquals(Map.of("2 NOTIFY", 408, "3 NOTIFY", 200), aliceAnswers);
		for (String name : bob.getAttributeNameSet()) {
			assertNull(name.startsWith("RESPONSE_CALLBACK_") ? name : null, "callback left behind");
		}
	}

	@Test
	void anInviteKeepsItsOneNamePerDialog() throws Exception {
		DetachedRequest invite = new DetachedRequest(app, "INVITE");
		invite.setHeader("CSeq", "7 INVITE");
		assertEquals("RESPONSE_CALLBACK_INVITE", Callflow.responseCallbackKey(invite));
		DetachedRequest notify = new DetachedRequest(app, "NOTIFY");
		notify.setHeader("CSeq", "7 NOTIFY");
		assertEquals("RESPONSE_CALLBACK_NOTIFY_7", Callflow.responseCallbackKey(notify));
	}
}
