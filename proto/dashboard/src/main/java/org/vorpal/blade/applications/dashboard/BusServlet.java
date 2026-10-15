package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Whether the board can be trusted: the event bus as the broker sees it.
/// Every durable subscription and what waits for it, and each event parked on
/// the error queue (its type, subject, when it was sent, and why the broker
/// parked it).
///
/// URL: `/blade/dashboard/bus`. `GET` reads; `POST ?delete=<JMSMessageID>` or
/// `?delete=all` deletes parked events, which is the dashboard's one action
/// that changes anything.
///
/// **Delete only, never replay.** A parked event does not say which subscriber
/// failed it, and a replay goes to every subscriber on the topic: analytics
/// would skip it as a duplicate, while transfer would act on a request twice.
/// An event worth keeping is fixed at its cause and published again by its
/// application.
///
/// **Who may delete.** The WebLogic `Admin` or `Operator` role, on a request
/// that carries `X-Blade-Board`. A cross-site form cannot set the header, and
/// a cross-site script cannot without the browser asking first.
@WebServlet("/bus")
public class BusServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(BusServlet.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static final String ERROR_QUEUE = "BladeEventBusErrorQueue";
	/// At most this many parked events are read per request.
	static final int MAX_PARKED = 200;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (Access.refusePlatform(req, resp, getServletContext())) {
			return;
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		ObjectNode out = MAPPER.createObjectNode();
		try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			out.set("bus", NetworkServlet.bus(dr));
			ArrayNode parked = out.putArray("parked");
			for (ObjectName queue : errorQueues(dr)) {
				parked(dr, queue, parked);
			}
		} catch (Exception e) {
			logger.log(Level.FINE, "bus read failed", e);
			out.put("error", "the broker could not be read: " + e);
		}
		out.put("mayDelete", mayDelete(req));
		out.put("asOf", System.currentTimeMillis());
		MAPPER.writeValue(resp.getWriter(), out);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (Access.refusePlatform(req, resp, getServletContext())) {
			return;
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		String which = req.getParameter("delete");
		if (!mayDelete(req) || req.getHeader("X-Blade-Board") == null) {
			resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
			resp.getWriter().write("{\"error\":\"deleting parked events needs the Admin or Operator role\"}");
			return;
		}
		if (which == null || !("all".equals(which) || which.matches("ID:<[0-9.]+>"))) {
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			resp.getWriter().write("{\"error\":\"delete=all or delete=<JMSMessageID>\"}");
			return;
		}
		String selector = "all".equals(which) ? "" : "JMSMessageID = '" + which + "'";
		long deleted = 0;
		try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			for (ObjectName queue : errorQueues(dr)) {
				Object n = dr.invoke(queue, "deleteMessages", new Object[] { selector },
						new String[] { String.class.getName() });
				deleted += n instanceof Number ? ((Number) n).longValue() : 0;
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "deleting parked events failed", e);
			resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			MAPPER.writeValue(resp.getWriter(), java.util.Collections.singletonMap("error", String.valueOf(e)));
			return;
		}
		logger.info("dashboard: " + req.getRemoteUser() + " deleted " + deleted + " parked event(s) (" + which + ")");
		MAPPER.writeValue(resp.getWriter(), java.util.Collections.singletonMap("deleted", deleted));
	}

	static boolean mayDelete(HttpServletRequest req) {
		return req.isUserInRole("Admin") || req.isUserInRole("Operator");
	}

	static java.util.List<ObjectName> errorQueues(MBeanServer dr) throws Exception {
		java.util.List<ObjectName> out = new java.util.ArrayList<>();
		for (ObjectName on : dr.queryNames(new ObjectName("com.bea:Type=JMSDestinationRuntime,*"), null)) {
			if (String.valueOf(on.getKeyProperty("Name")).contains(ERROR_QUEUE)) {
				out.add(on);
			}
		}
		return out;
	}

	/// Each parked event's headers, read through a browse cursor, which takes
	/// nothing off the queue.
	private static void parked(MBeanServer dr, ObjectName queue, ArrayNode out) throws Exception {
		Object count = dr.getAttribute(queue, "MessagesCurrentCount");
		if (!(count instanceof Number) || ((Number) count).longValue() == 0) {
			return;
		}
		String cursor = (String) dr.invoke(queue, "getMessages", new Object[] { "", Integer.valueOf(0) },
				new String[] { String.class.getName(), Integer.class.getName() });
		try {
			CompositeData[] items = (CompositeData[]) dr.invoke(queue, "getNext",
					new Object[] { cursor, Integer.valueOf(MAX_PARKED) },
					new String[] { String.class.getName(), Integer.class.getName() });
			for (CompositeData item : items == null ? new CompositeData[0] : items) {
				ObjectNode row = ParkedEvents.message(String.valueOf(item.get("MessageXMLText")));
				if (row != null) {
					row.put("server", queue.getKeyProperty("Location"));
					out.add(row);
				}
			}
		} finally {
			dr.invoke(queue, "closeCursor", new Object[] { cursor }, new String[] { String.class.getName() });
		}
	}

}
