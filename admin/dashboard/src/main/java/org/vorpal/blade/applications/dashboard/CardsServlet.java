package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v3.configuration.AppCards;

/// The application list for the rail and the board's links ([AppCards]).
///
/// URL: `/blade/dashboard/cards`.
///
/// The dashboard serves it rather than reading the portal's copy because the
/// two sign people in differently: a viewer signed in here by OpenID Connect
/// holds no session in the portal, whose container login answers a fetch with
/// its sign-in page.
@WebServlet("/cards")
public class CardsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(CardsServlet.class.getName());

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (Access.refusePlatform(req, resp, getServletContext())) {
			return;
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			resp.getWriter().write(AppCards.json(dr));
		} catch (Exception e) {
			logger.log(Level.WARNING, "application list failed", e);
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}
}
