package org.vorpal.blade.applications.dashboard;

import javax.servlet.http.HttpServletRequest;

import org.vorpal.blade.framework.v3.security.OidcLoginFilter;

/// Where the account menu's "Sign out" ([SignOutServlet]) sends a request.
final class SignOut {

	/// The portal's sign-out of every application on this server.
	static final String EVERYWHERE = "/blade/portal/logout";

	private SignOut() {
	}

	/// An identity-domain sign-in by way of the identity domain, which needs
	/// the ID token this application holds; a WebLogic one straight there.
	static String target(HttpServletRequest req) {
		if (OidcLoginFilter.AUTH_TYPE.equals(req.getAuthType())) {
			return req.getContextPath() + OidcLoginFilter.LOGOUT_PATH + "?" + OidcLoginFilter.THEN_PARAM + "="
					+ EVERYWHERE;
		}
		return EVERYWHERE;
	}
}
