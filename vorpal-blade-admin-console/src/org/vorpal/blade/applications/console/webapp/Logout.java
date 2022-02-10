package org.vorpal.blade.applications.console.webapp;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class Logout extends HttpServlet {

	@Override
	public final void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		response.setHeader("Cache-Control", "no-cache, no-store");
		response.setHeader("Pragma", "no-cache");
		request.getSession().invalidate();
		response.sendRedirect((new StringBuilder()).append(request.getContextPath()).append("/login.jsp").toString());
	}

}
