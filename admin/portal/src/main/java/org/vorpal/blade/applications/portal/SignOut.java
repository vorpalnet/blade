package org.vorpal.blade.applications.portal;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/// Ends every sign-in this browser holds on the server.
///
/// The admin applications share one session cookie, so a WebLogic login lives
/// on in every other application's session after one of them ends its own.
/// WebLogic's `ServletAuthentication.invalidateAll` ends them all.
/// Applications on another server (the engine tier) keep their sessions,
/// which end on their own or through their own sign-out.
final class SignOut {

	private static final Logger log = Logger.getLogger(SignOut.class.getName());

	private SignOut() {
	}

	/// Every session on this server, then the container login. Outside
	/// WebLogic, this application's session alone.
	static void everywhere(HttpServletRequest request) {
		if (!invalidateAll(request)) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
		}
		try {
			request.logout();
		} catch (ServletException e) {
			log.log(Level.FINE, "container logout failed", e);
		}
	}

	/// `weblogic.servlet.security.ServletAuthentication.invalidateAll`, called
	/// by reflection: the class is in the server's servlet module, which the
	/// build's WebLogic dependency does not put on the compile path. False
	/// where it is missing or cannot run (outside WebLogic).
	static boolean invalidateAll(HttpServletRequest request) {
		try {
			Method m = Class.forName("weblogic.servlet.security.ServletAuthentication").getMethod("invalidateAll",
					HttpServletRequest.class);
			m.invoke(null, request);
			return true;
		} catch (ReflectiveOperationException | LinkageError e) {
			log.log(Level.FINE, "invalidateAll unavailable", e);
			return false;
		}
	}
}
