package org.vorpal.blade.applications.dashboard;

import java.io.IOException;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// What is wrong now and what just happened, from the operations events
/// ([OpsFeed]), and the calls waiting in a queue now, from the queue
/// application's gauges.
///
/// URL: `/blade/dashboard/ops`.
@WebServlet("/ops")
public class OpsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (Access.refusePlatform(req, resp, getServletContext())) {
			return;
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		ObjectNode out = OpsFeed.FEED.snapshot();
		out.set("queuedNow", queuedNow());
		out.set("remotesDown", remotesDown());
		resp.getWriter().write(out.toString());
	}

	/// The services an application has cut off right now: every breaker that
	/// is open, read live from each application's `Remotes` MBean. Read live
	/// rather than kept from the `remote.down` events, because an application
	/// redeployed while its breaker was open never publishes the `remote.up`,
	/// and a list kept from events would show it down for good.
	private static com.fasterxml.jackson.databind.node.ArrayNode remotesDown() {
		com.fasterxml.jackson.databind.node.ArrayNode out = MAPPER.createArrayNode();
		try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			for (JsonNode r : NetworkServlet.remotes(dr)) {
				if (r.path("open").asBoolean()) {
					ObjectNode e = out.addObject();
					e.put("kind", r.path("kind").asText());
					e.put("remote", r.path("remote").asText());
					e.put("application", r.path("application").asText());
					e.put("node", r.path("server").asText());
					e.put("reason", r.path("lastFailure").asText(null));
					e.put("time", java.time.Instant.ofEpochMilli(r.path("lastFailureAt").asLong()).toString());
				}
			}
		} catch (Exception e) {
			// a read that fails shows nothing down rather than failing the feed
		}
		return out;
	}

	/// The queue application's metrics MBeans, one per node.
	static final String QUEUE_METRICS = "vorpal.blade:Name=queue,Type=Metrics,*";

	/// Calls waiting in a queue right now, read live from the queue application's
	/// gauges on every node: `calls` summed, `longestMs` the longest wait on any
	/// node, `nodes` how many answered. Null values when no node runs the queue
	/// application, so the board can tell "none waiting" from "nothing to ask".
	///
	/// Live rather than from the `queue.depth` events: those come once a minute
	/// and only while a queue is busy.
	private static ObjectNode queuedNow() {
		ObjectNode out = MAPPER.createObjectNode();
		long calls = 0, longest = 0;
		int nodes = 0;
		try (HealthServlet.CloseableContext ctx = new HealthServlet.CloseableContext()) {
			MBeanServer dr = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			for (ObjectName on : dr.queryNames(new ObjectName(QUEUE_METRICS), null)) {
				Object json = HealthServlet.get(dr, on, "ReportJson");
				if (!(json instanceof String)) {
					continue;
				}
				JsonNode gauges = MAPPER.readTree((String) json).path("gauges");
				boolean seen = false;
				for (JsonNode g : gauges) {
					switch (g.path("name").asText()) {
					case "queued":
						calls += g.path("value").asLong();
						seen = true;
						break;
					case "queuedLongestMs":
						longest = Math.max(longest, g.path("value").asLong());
						break;
					default:
						break;
					}
				}
				if (seen) {
					nodes++;
				}
			}
		} catch (Exception e) {
			out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		if (nodes == 0) {
			out.putNull("calls");
			out.putNull("longestMs");
		} else {
			out.put("calls", calls);
			out.put("longestMs", longest);
		}
		out.put("nodes", nodes);
		return out;
	}
}
