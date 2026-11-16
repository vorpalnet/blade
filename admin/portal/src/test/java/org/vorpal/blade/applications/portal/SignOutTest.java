package org.vorpal.blade.applications.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.junit.jupiter.api.Test;

/// Outside WebLogic there is no `invalidateAll`: the portal still ends its own
/// session and the container login.
class SignOutTest {

	@Test
	void withoutWebLogicItEndsItsOwnSessionAndLogin() {
		AtomicInteger invalidated = new AtomicInteger();
		AtomicInteger loggedOut = new AtomicInteger();
		HttpSession session = (HttpSession) Proxy.newProxyInstance(HttpSession.class.getClassLoader(),
				new Class<?>[] { HttpSession.class }, (p, m, a) -> {
					if (m.getName().equals("invalidate")) {
						invalidated.incrementAndGet();
					}
					return null;
				});
		HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
				HttpServletRequest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class }, (p, m, a) -> {
					switch (m.getName()) {
					case "getSession":
						return session;
					case "logout":
						loggedOut.incrementAndGet();
						return null;
					default:
						return m.getReturnType() == boolean.class ? false : null;
					}
				});

		SignOut.everywhere(req);
		assertEquals(1, invalidated.get(), "this application's session");
		assertEquals(1, loggedOut.get(), "the container login");
	}
}
