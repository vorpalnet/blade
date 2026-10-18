package org.vorpal.blade.framework;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

import org.junit.jupiter.api.Test;

/// The first dialog to answer an initial INVITE is the call; a 2xx from any other dialog of
/// the same INVITE (a forking proxy downstream) is an extra answer to acknowledge and end.
class ExtraAnswerTest {

	@Test
	void theFirstAnswerIsTheCallAndAnotherDialogsIsExtra() {
		SipApplicationSession app = appSession();
		assertFalse(AsyncSipServlet.isExtraAnswer(answer(app, "call-1", "dialog-a", true), app));
		assertTrue(AsyncSipServlet.isExtraAnswer(answer(app, "call-1", "dialog-b", true), app));
		assertFalse(AsyncSipServlet.isExtraAnswer(answer(app, "call-1", "dialog-a", true), app),
				"the first dialog's own 2xx again is not extra");
	}

	@Test
	void separateInvitesAndReInvitesAreNotForks() {
		SipApplicationSession app = appSession();
		assertFalse(AsyncSipServlet.isExtraAnswer(answer(app, "call-1", "dialog-a", true), app));
		assertFalse(AsyncSipServlet.isExtraAnswer(answer(app, "call-2", "dialog-b", true), app),
				"another INVITE has its own Call-ID");
		assertFalse(AsyncSipServlet.isExtraAnswer(answer(app, "call-1", "dialog-c", false), app),
				"a re-INVITE's answer is never extra");
	}

	private static SipApplicationSession appSession() {
		Map<String, Object> attrs = new HashMap<>();
		return (SipApplicationSession) Proxy.newProxyInstance(ExtraAnswerTest.class.getClassLoader(),
				new Class<?>[] { SipApplicationSession.class }, (proxy, method, a) -> {
					switch (method.getName()) {
					case "getAttribute":
						return attrs.get(a[0]);
					case "setAttribute":
						attrs.put((String) a[0], a[1]);
						return null;
					default:
						return null;
					}
				});
	}

	private static SipServletResponse answer(SipApplicationSession app, String callId, String dialog,
			boolean initial) {
		SipSession session = (SipSession) Proxy.newProxyInstance(ExtraAnswerTest.class.getClassLoader(),
				new Class<?>[] { SipSession.class },
				(proxy, method, a) -> "getId".equals(method.getName()) ? dialog : null);
		SipServletRequest request = (SipServletRequest) Proxy.newProxyInstance(
				ExtraAnswerTest.class.getClassLoader(), new Class<?>[] { SipServletRequest.class },
				(proxy, method, a) -> "isInitial".equals(method.getName()) ? initial : null);
		return (SipServletResponse) Proxy.newProxyInstance(ExtraAnswerTest.class.getClassLoader(),
				new Class<?>[] { SipServletResponse.class }, (proxy, method, a) -> {
					switch (method.getName()) {
					case "getRequest":
						return request;
					case "getSession":
						return session;
					case "getCallId":
						return callId;
					case "getApplicationSession":
						return app;
					default:
						return null;
					}
				});
	}
}
