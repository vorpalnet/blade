package org.vorpal.blade.applications.dashboard;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v3.security.OidcLoginFilter;

/// The account menu's "Sign out", for either way in. Both end at the portal's
/// `/logout` ([SignOut#EVERYWHERE]), which signs the browser out of every application
/// on the AdminServer: they share one session cookie, so ending only this
/// application's session would leave the others signed in.
///
/// An identity-domain session first goes to [OidcLoginFilter#LOGOUT_PATH],
/// which ends the session at the identity domain with the ID token this
/// application holds, so the next visit asks for a password again rather
/// than signing straight back in; the identity domain then returns the
/// browser to the portal. A WebLogic form login goes to the portal directly.
///
/// URL: `/blade/dashboard/signout`.
@WebServlet("/signout")
public class SignOutServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.sendRedirect(SignOut.target(req));
	}
}
