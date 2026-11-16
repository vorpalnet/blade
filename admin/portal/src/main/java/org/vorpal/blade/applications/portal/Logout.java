package org.vorpal.blade.applications.portal;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/// GET /logout: signs the browser out of every application on this server
/// ([SignOut#everywhere]) and shows the login page.
///
/// An identity-domain sign-in reaches here from an application's
/// `/oidc/logout?then=/blade/portal/logout`, which first ends the session at
/// the identity domain with the ID token only that application holds. The
/// path is public in `web.xml` for that reason: the identity domain returns
/// a browser with no WebLogic login.
public class Logout extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	public final void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setHeader("Cache-Control", "no-cache, no-store");
		response.setHeader("Pragma", "no-cache");
		SignOut.everywhere(request);
		response.sendRedirect(request.getContextPath() + "/login.jsp");
	}
}
