package org.vorpal.blade.services.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;

import org.junit.Test;

/// The two lifecycle rules the emit sites lean on, checked without a container.
///
/// Neither rule can be read off the message in hand at the moment a call ends, which is exactly why
/// they live in [CallEvents] rather than at each of the eight teardown sites that would otherwise
/// have to agree with each other.
public class CallEventsTest {

	/// A [SipApplicationSession] that is nothing but its attribute map. Everything in this test
	/// turns on `getAttribute`/`setAttribute`, and the rest of that interface is thirty-odd methods
	/// of container machinery no rule here consults.
	private static SipApplicationSession session() {
		final Map<String, Object> attributes = new HashMap<>();
		return (SipApplicationSession) Proxy.newProxyInstance(
				CallEventsTest.class.getClassLoader(),
				new Class<?>[] { SipApplicationSession.class },
				new InvocationHandler() {
					@Override
					public Object invoke(Object proxy, Method method, Object[] args) {
						switch (method.getName()) {
						case "getAttribute":
							return attributes.get(args[0]);
						case "setAttribute":
							return attributes.put((String) args[0], args[1]);
						case "removeAttribute":
							return attributes.remove(args[0]);
						default:
							return null;
						}
					}
				});
	}

	@Test
	public void aCallThatWasNeverAnsweredIsAbandoned() {
		// The browser hung up while the far end was still ringing, or the caller gave up while the
		// browser rang. Recording that as "completed" would put calls nobody ever had into a
		// completion rate.
		assertEquals(CallEvents.ABANDONED, CallEvents.terminalName(session()));
	}

	@Test
	public void aCallThatReachedAnAnswerIsCompleted() {
		SipApplicationSession app = session();
		CallEvents.markAnswered(app);

		assertEquals(CallEvents.COMPLETED, CallEvents.terminalName(app));
	}

	@Test
	public void aMissingSessionIsAbandonedRatherThanAnException() {
		// Teardown paths run after invalidation; a null session must not become a thrown exception
		// on the way out of a call.
		assertEquals(CallEvents.ABANDONED, CallEvents.terminalName(null));
		assertFalse(CallEvents.claimTerminal(null));
	}

	@Test
	public void onlyTheFirstPathToEndACallPublishes() {
		// cancelOrBye can publish an abandon, have its CANCEL throw, and fall through to
		// byeAndRelease; inbound, a browser decline does not cancel the BYE expectation armed while
		// it was ringing. Analytics.sessionStop has no idempotence of its own, so this guard is the
		// only thing between those races and two terminal facts for one call.
		SipApplicationSession app = session();

		assertTrue("the first path to arrive owns the ending", CallEvents.claimTerminal(app));
		assertFalse("the second must be silent", CallEvents.claimTerminal(app));
		assertFalse("and so must every one after it", CallEvents.claimTerminal(app));
	}

	@Test
	public void answeringDoesNotEndTheCall() {
		// The two flags are independent: reaching a 200 OK decides *which* terminal fact is right,
		// not that one has been published.
		SipApplicationSession app = session();
		CallEvents.markAnswered(app);

		assertTrue(CallEvents.claimTerminal(app));
		assertEquals(CallEvents.COMPLETED, CallEvents.terminalName(app));
	}
}
