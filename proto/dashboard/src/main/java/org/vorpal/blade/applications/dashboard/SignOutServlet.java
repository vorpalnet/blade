package org.vorpal.blade.applications.dashboard;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.vorpal.blade.framework.v3.security.OidcLoginFilter;

/// The account menu's "Sign out", for either way in.
///
/// An identity-domain session goes to [OidcLoginFilter#LOGOUT_PATH], which
/// also ends the session at the identity domain, so the next visit asks for a
/// password again rather than signing straight back in. A WebLogic form
/// login has no such endpoint: the filter passes a container-authenticated
/// request through untouched. That session is ended here, and the board's
/// next load shows the login form.
///
/// URL: `/blade/dashboard/signout`.
@WebServlet("/signout")
public class SignOutServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		if (OidcLoginFilter.AUTH_TYPE.equals(req.getAuthType())) {
			resp.sendRedirect(req.getContextPath() + OidcLoginFilter.LOGOUT_PATH);
			return;
		}
		req.logout();
		HttpSession session = req.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		resp.sendRedirect(req.getContextPath() + "/");
	}
}
