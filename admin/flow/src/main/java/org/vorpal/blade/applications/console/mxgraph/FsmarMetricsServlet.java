package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// JMX bridge to the FSMAR 3 metrics MBean
/// (`org.vorpal.blade:type=Fsmar3,name=metrics`) on every engine in the
/// domain — the Flow editor's live heat overlay and capture/replay backend.
///
/// All reads/invokes go over the federated DomainRuntime MBeanServer
/// (`java:comp/env/jmx/domainRuntime`), the admin tier's internal-
/// communication convention — the same path the portal walks SettingsMXBeans
/// with, which proves engine-side platform-MBeanServer registrations are
/// visible here. The authenticated user's identity propagates implicitly.
///
/// Operations (`op` parameter; GET for reads, POST accepted everywhere):
/// - `hits` (default) — counters summed across engines plus per-transition
///   hit counts parsed into `{state, method, id, count}` records keyed for
///   the diagram overlay
/// - `traces` — every captured routing trace (the shared trace format; see
///   `RouteTrace` in libs/fsmar), tagged with the engine it came from
/// - `capture&count=N` — arm trace capture for the next N calls **per
///   engine** (a cluster of 4 armed with 10 yields up to 40 traces)
/// - `clear` — discard captured traces on every engine
/// - `reset` — zero the counters on every engine
@WebServlet("/fsmarMetrics")
public class FsmarMetricsServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private static final Logger log = Logger.getLogger(FsmarMetricsServlet.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();

	/// Trailing `,*` so federation-added key properties (e.g. Location) still
	/// match.
	private static final String METRICS_PATTERN = "org.vorpal.blade:type=Fsmar3,name=metrics,*";

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		dispatch(request, response);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		dispatch(request, response);
	}

	private void dispatch(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String op = request.getParameter("op");
		if (op == null || op.isEmpty()) {
			op = "hits";
		}

		try (CloseableContext ctx = new CloseableContext()) {
			MBeanServer mbs = (MBeanServer) ctx.lookup("java:comp/env/jmx/domainRuntime");
			Set<ObjectInstance> instances = mbs.queryMBeans(new ObjectName(METRICS_PATTERN), null);

			ObjectNode out;
			switch (op) {
			case "hits":
				out = hits(mbs, instances);
				break;
			case "traces":
				out = traces(mbs, instances);
				break;
			case "capture":
				out = capture(mbs, instances, request.getParameter("count"));
				break;
			case "clear":
				out = invokeAll(mbs, instances, "clearCapturedTraces");
				break;
			case "reset":
				out = invokeAll(mbs, instances, "resetCounters");
				break;
			default:
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unknown op '" + op + "'");
				return;
			}

			out.put("servers", instances.size());
			response.setContentType("application/json; charset=UTF-8");
			PrintWriter w = response.getWriter();
			w.write(mapper.writeValueAsString(out));
			w.flush();
		} catch (Exception e) {
			log.log(Level.WARNING, "FSMAR metrics bridge failed (op=" + op + ")", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"FSMAR metrics lookup failed: " + e.getClass().getSimpleName()
							+ ": " + (e.getMessage() != null ? e.getMessage() : ""));
		}
	}

	/// Counters summed across every engine's MBean; per-transition hit lines
	/// (`state/METHOD/transitionId = count`) parsed and aggregated by key.
	private ObjectNode hits(MBeanServer mbs, Set<ObjectInstance> instances) {
		long requests = 0, fallbacks = 0, bypasses = 0, cycles = 0, captureRemaining = 0;
		TreeMap<String, Long> byKey = new TreeMap<>();

		for (ObjectInstance inst : instances) {
			ObjectName on = inst.getObjectName();
			requests += longAttr(mbs, on, "RequestsRouted");
			fallbacks += longAttr(mbs, on, "DefaultApplicationFallbacks");
			bypasses += longAttr(mbs, on, "UndeployedBypasses");
			cycles += longAttr(mbs, on, "RoutingCyclesDetected");
			captureRemaining += longAttr(mbs, on, "CaptureRemaining");

			String[] lines = strArrAttr(mbs, on, "TransitionHits");
			if (lines == null) continue;
			for (String line : lines) {
				int eq = line.lastIndexOf(" = ");
				if (eq < 0) continue;
				String key = line.substring(0, eq);
				long count;
				try {
					count = Long.parseLong(line.substring(eq + 3).trim());
				} catch (NumberFormatException e) {
					continue;
				}
				byKey.merge(key, count, Long::sum);
			}
		}

		ObjectNode out = mapper.createObjectNode();
		out.put("requestsRouted", requests);
		out.put("defaultApplicationFallbacks", fallbacks);
		out.put("undeployedBypasses", bypasses);
		out.put("routingCyclesDetected", cycles);
		out.put("captureRemaining", captureRemaining);
		ArrayNode hits = out.putArray("transitionHits");
		for (java.util.Map.Entry<String, Long> e : byKey.entrySet()) {
			// key shape: state/METHOD/transitionId (state and method never
			// contain '/'; the id is whatever remains).
			String[] parts = e.getKey().split("/", 3);
			ObjectNode h = hits.addObject();
			h.put("state", parts.length > 0 ? parts[0] : "");
			h.put("method", parts.length > 1 ? parts[1] : "");
			h.put("id", parts.length > 2 ? parts[2] : "");
			h.put("count", e.getValue());
		}
		return out;
	}

	/// Captured traces from every engine, parsed back into objects and tagged
	/// with the engine they came from.
	private ObjectNode traces(MBeanServer mbs, Set<ObjectInstance> instances) {
		ObjectNode out = mapper.createObjectNode();
		ArrayNode arr = out.putArray("traces");
		for (ObjectInstance inst : instances) {
			ObjectName on = inst.getObjectName();
			String server = serverOf(on);
			String[] jsons = strArrAttr(mbs, on, "CapturedTraces");
			if (jsons == null) continue;
			for (String json : jsons) {
				try {
					ObjectNode trace = (ObjectNode) mapper.readTree(json);
					if (server != null) {
						trace.put("server", server);
					}
					arr.add(trace);
				} catch (Exception e) {
					// One unparsable trace shouldn't hide the rest.
				}
			}
		}
		return out;
	}

	private ObjectNode capture(MBeanServer mbs, Set<ObjectInstance> instances, String countParam) {
		int count = 10;
		try {
			if (countParam != null) {
				count = Integer.parseInt(countParam);
			}
		} catch (NumberFormatException ignore) {
		}
		for (ObjectInstance inst : instances) {
			try {
				mbs.invoke(inst.getObjectName(), "captureNextCalls",
						new Object[] { count }, new String[] { "int" });
			} catch (Exception e) {
				log.log(Level.WARNING, "captureNextCalls failed on " + inst.getObjectName(), e);
			}
		}
		ObjectNode out = mapper.createObjectNode();
		out.put("armed", count);
		return out;
	}

	private ObjectNode invokeAll(MBeanServer mbs, Set<ObjectInstance> instances, String operation) {
		for (ObjectInstance inst : instances) {
			try {
				mbs.invoke(inst.getObjectName(), operation, new Object[0], new String[0]);
			} catch (Exception e) {
				log.log(Level.WARNING, operation + " failed on " + inst.getObjectName(), e);
			}
		}
		ObjectNode out = mapper.createObjectNode();
		out.put("ok", true);
		return out;
	}

	/// Best-effort engine name from federation-added key properties.
	private static String serverOf(ObjectName on) {
		String s = on.getKeyProperty("Location");
		if (s == null) {
			s = on.getKeyProperty("ServerRuntime");
		}
		return s;
	}

	private static long longAttr(MBeanServer mbs, ObjectName on, String name) {
		try {
			Object v = mbs.getAttribute(on, name);
			return (v instanceof Number) ? ((Number) v).longValue() : 0L;
		} catch (Exception e) {
			return 0L;
		}
	}

	private static String[] strArrAttr(MBeanServer mbs, ObjectName on, String name) {
		try {
			return (String[]) mbs.getAttribute(on, name);
		} catch (Exception e) {
			return null;
		}
	}
}
