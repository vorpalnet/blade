package org.vorpal.blade.applications.dashboard;

import java.io.IOException;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/// What is wrong now and what just happened, from the operations events
/// ([OpsFeed]).
///
/// URL: `/blade/dashboard/ops`.
@WebServlet("/ops")
public class OpsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		resp.getWriter().write(OpsFeed.FEED.snapshot().toString());
	}
}
