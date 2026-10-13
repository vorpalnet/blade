package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/// Live cluster health from the runtime MBeans: the operational view the
/// WebLogic Remote Console makes hard to reach. Reads the federated Domain
/// Runtime MBean Server (`java:comp/env/jmx/domainRuntime`) once and emits one
/// row per server the domain defines. A running server has its node state, JVM
/// heap and CPU, thread pool, JDBC pools, plus OCCAS's `SipServerRuntime` session counts
/// and throughput and the BLADE drain flag. One that is not running has only its
/// lifecycle state and cluster, and `"up": false`.
///
/// The idiom is the one `admin/tuning/ServerDrain` established: reach everything
/// by ObjectName + attribute (no compile dependency on the other apps), attribute
/// names come from Oracle's own BeanInfo, and every read fails soft to -1/null,
/// so a renamed or absent attribute degrades to "n/a" rather than erroring the
/// whole panel. No JAX-RS; JSON is written by hand so the WAR stays framework-only.
///
/// URL: `/blade/dashboard/health`.
@WebServlet("/health")
public class HealthServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(HealthServlet.class.getName());
	private static final String DOMAIN_RUNTIME = "java:comp/env/jmx/domainRuntime";
	static final String DRS = "com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean";
	private static final String SIP_PATTERN = "com.bea:Type=SipServerRuntime,*";
	private static final String DRAIN_PATTERN = "vorpal.blade:Name=*,Type=Drain,*";

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (Access.refusePlatform(req, resp, getServletContext())) {
			return;
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");
		try (CloseableContext ctx = new CloseableContext(); PrintWriter out = resp.getWriter()) {
			nodes((MBeanServer) ctx.lookup(DOMAIN_RUNTIME), out);
		} catch (Exception e) {
			logger.log(Level.WARNING, "cluster health read failed", e);
			resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			resp.getWriter().write("{\"error\":\"" + esc(e.getClass().getSimpleName() + ": " + e.getMessage()) + "\"}");
		}
	}

	private void nodes(MBeanServer dr, PrintWriter out) throws Exception {
		String admin = adminName(dr);
		Map<String, long[]> sip = sipByServer(dr, admin);       // server -> [app, sip, throughput]
		Map<String, Boolean> drained = drainByServer(dr, admin);

		ObjectName[] servers = (ObjectName[]) dr.getAttribute(new ObjectName(DRS), "ServerRuntimes");
		// This domain's name: the board reads these servers live, and every other
		// domain from its samples.
		ObjectName config = obj(dr, new ObjectName(DRS), "DomainConfiguration");
		String domain = config == null ? null : str(dr, config, "Name");
		out.write("{\"domain\":" + (domain == null ? "null" : ("\"" + esc(domain) + "\"")) + ",\"nodes\":[");
		boolean first = true;
		java.util.Set<String> up = new java.util.HashSet<>();
		for (ObjectName sr : servers) {
			String name = str(dr, sr, "Name");
			if (name == null) name = "?";
			up.add(name);
			if (!first) out.write(',');
			first = false;
			out.write("{\"server\":\"" + esc(name) + "\",\"state\":\"" + esc(str(dr, sr, "State")) + "\"");

			// Cluster membership: a clustered server has a ClusterRuntime child; the
			// AdminServer does not, which is exactly how it stands alone in the UI.
			ObjectName cr = obj(dr, sr, "ClusterRuntime");
			String cluster = cr == null ? null : str(dr, cr, "Name");
			out.write(",\"cluster\":" + (cluster == null ? "null" : ("\"" + esc(cluster) + "\"")));

			ObjectName jvm = obj(dr, sr, "JVMRuntime");
			long freePct = jvm == null ? -1 : lng(dr, jvm, "HeapFreePercent");
			long heapMax = jvm == null ? -1 : lng(dr, jvm, "HeapSizeMax");
			out.write(",\"heapUsedPct\":" + (freePct < 0 ? "null" : Long.toString(100 - freePct)));
			out.write(",\"heapMaxMb\":" + (heapMax < 0 ? "null" : Long.toString(heapMax / (1024 * 1024))));
			Long cpu = cpuPct(dr, name);
			out.write(",\"cpuPct\":" + (cpu == null ? "null" : cpu.toString()));

			ObjectName tp = obj(dr, sr, "ThreadPoolRuntime");
			out.write(",\"threadsTotal\":" + num(tp == null ? -1 : lng(dr, tp, "ExecuteThreadTotalCount")));
			out.write(",\"threadsStuck\":" + num(tp == null ? -1 : lng(dr, tp, "StuckThreadCount")));
			out.write(",\"threadsPending\":" + num(tp == null ? -1 : lng(dr, tp, "PendingUserRequestCount")));

			out.write(",\"jdbc\":");
			jdbc(dr, name, obj(dr, sr, "JDBCServiceRuntime"), out);

			long[] s = sip.get(name);
			out.write(",\"appSessions\":" + (s == null ? "null" : num(s[0])));
			out.write(",\"sipSessions\":" + (s == null ? "null" : num(s[1])));
			out.write(",\"sipThroughput\":" + (s == null ? "null" : num(s[2])));

			Boolean d = drained.get(name);
			out.write(",\"drained\":" + (d == null ? "false" : d.toString()) + '}');
		}
		// Every server the domain defines that is not up: a server shut down,
		// failed or not yet started has no ServerRuntime, so the list above
		// never names it, and a domain of five with two stopped would read
		// "3 / 3". A dynamic cluster's servers are counted to its current size.
		Map<String, String> clusterOf = clusterOf(dr, config);
		for (Map.Entry<String, String> down : notRunning(dr, up).entrySet()) {
			if (!first) out.write(',');
			first = false;
			String cluster = clusterOf.get(down.getKey());
			out.write("{\"server\":\"" + esc(down.getKey()) + "\",\"state\":\"" + esc(down.getValue()) + "\",\"cluster\":"
					+ (cluster == null ? "null" : ("\"" + esc(cluster) + "\"")) + ",\"up\":false}");
		}
		out.write("],\"asOf\":" + System.currentTimeMillis() + '}');
	}

	/// The CPU a server's JVM used since the previous read of this endpoint,
	/// as a percentage of all its processors: the change in `ProcessCpuTime`
	/// over the wall time between reads. The board polls every few seconds, so
	/// this is a few seconds' average, where `ProcessCpuLoad` is one instant's
	/// reading. Null on the first read and after a restart.
	private Long cpuPct(MBeanServer dr, String server) {
		try {
			ObjectName os = new ObjectName("java.lang:Location=" + server + ",type=OperatingSystem");
			long cpuNs = lng(dr, os, "ProcessCpuTime");
			long processors = lng(dr, os, "AvailableProcessors");
			long now = System.currentTimeMillis();
			if (cpuNs < 0 || processors <= 0) {
				return null;
			}
			long[] last = cpuLast.put(server, new long[] { cpuNs, now });
			if (last == null || cpuNs < last[0] || now - last[1] < 500) {
				return null;
			}
			return Math.round(Math.min(100.0, 100.0 * ((cpuNs - last[0]) / 1_000_000.0) / ((now - last[1]) * (double) processors)));
		} catch (Exception e) {
			return null;
		}
	}

	/// Server name to its last `ProcessCpuTime` (ns) and when it was read (ms).
	private final Map<String, long[]> cpuLast = new java.util.concurrent.ConcurrentHashMap<>();

	/// Name to lifecycle state of each defined server not in `up`, from the
	/// domain's ServerLifeCycleRuntimes: one per server the domain defines,
	/// dynamic ones included, whether or not it runs.
	private static Map<String, String> notRunning(MBeanServer dr, java.util.Set<String> up) {
		Map<String, String> out = new java.util.TreeMap<>();
		try {
			ObjectName domainRuntime = obj(dr, new ObjectName(DRS), "DomainRuntime");
			ObjectName[] lcs = (ObjectName[]) dr.getAttribute(domainRuntime, "ServerLifeCycleRuntimes");
			for (ObjectName lc : lcs) {
				String name = str(dr, lc, "Name");
				if (name != null && !up.contains(name)) {
					String state = str(dr, lc, "State");
					out.put(name, state == null ? "UNKNOWN" : state);
				}
			}
		} catch (Exception e) {
			logger.log(Level.FINE, "server lifecycle read failed", e);
		}
		return out;
	}

	/// Server name to cluster name, for static servers and each dynamic
	/// cluster's calculated servers.
	private static Map<String, String> clusterOf(MBeanServer dr, ObjectName config) {
		Map<String, String> out = new HashMap<>();
		if (config == null) {
			return out;
		}
		try {
			for (ObjectName s : (ObjectName[]) dr.getAttribute(config, "Servers")) {
				ObjectName c = obj(dr, s, "Cluster");
				if (c != null) {
					out.put(str(dr, s, "Name"), str(dr, c, "Name"));
				}
			}
			for (ObjectName c : (ObjectName[]) dr.getAttribute(config, "Clusters")) {
				ObjectName dyn = obj(dr, c, "DynamicServers");
				Object names = dyn == null ? null : get(dr, dyn, "DynamicServerNames");
				if (names instanceof String[]) {
					for (String n : (String[]) names) {
						out.put(n, str(dr, c, "Name"));
					}
				}
			}
		} catch (Exception e) {
			logger.log(Level.FINE, "cluster membership read failed", e);
		}
		return out;
	}

	/// `server|pool` → the failure total last read and when it last rose.
	private static final Map<String, long[]> JDBC_FAILURES = new java.util.concurrent.ConcurrentHashMap<>();

	/// Each connection pool on a server: its connections, its state
	/// (`Running`, `Suspended`, `Overloaded`, `Unhealthy`, `Shutdown`), and its
	/// failures: reserve requests refused or timed out, and reconnects that
	/// failed. WebLogic counts those from the pool's start, so `failuresAt` says
	/// when the total last rose, which is what tells a problem now from one
	/// last week; 0 until this server has seen it rise.
	private void jdbc(MBeanServer dr, String server, ObjectName svc, PrintWriter out) {
		out.write('[');
		if (svc != null) {
			try {
				ObjectName[] dss = (ObjectName[]) dr.getAttribute(svc, "JDBCDataSourceRuntimeMBeans");
				boolean first = true;
				if (dss != null) for (ObjectName ds : dss) {
					if (!first) out.write(',');
					first = false;
					String name = str(dr, ds, "Name");
					// FailedReserveRequestCount already counts the reserves that timed
					// out waiting (WaitingForConnectionFailureTotal): adding both
					// counts each of those twice.
					long failures = Math.max(0, lng(dr, ds, "FailedReserveRequestCount"))
							+ Math.max(0, lng(dr, ds, "FailuresToReconnectCount"));
					long[] seen = JDBC_FAILURES.computeIfAbsent(server + "|" + name, k -> new long[] { failures, 0 });
					if (failures > seen[0]) {
						seen[1] = System.currentTimeMillis();
					}
					seen[0] = failures;
					out.write("{\"name\":\"" + esc(name) + "\",\"active\":" + num(lng(dr, ds, "ActiveConnectionsCurrentCount"))
							+ ",\"capacity\":" + num(lng(dr, ds, "CurrCapacity"))
							+ ",\"waiting\":" + num(lng(dr, ds, "WaitingForConnectionCurrentCount"))
							+ ",\"state\":\"" + esc(str(dr, ds, "State")) + "\""
							+ ",\"failures\":" + failures
							+ ",\"failuresAt\":" + seen[1]
							+ ",\"leaked\":" + num(lng(dr, ds, "LeakedConnectionCount")) + '}');
				}
			} catch (Exception ignore) { }
		}
		out.write(']');
	}

	private Map<String, long[]> sipByServer(MBeanServer dr, String admin) {
		Map<String, long[]> m = new LinkedHashMap<>();
		try {
			for (ObjectName on : dr.queryNames(new ObjectName(SIP_PATTERN), null)) {
				m.put(serverOf(on, admin), new long[] {
						lng(dr, on, "ActiveServerAppSessionCount"),
						lng(dr, on, "ActiveServerSipSessionCount"),
						lng(dr, on, "PeriodCountSipThroughput") });
			}
		} catch (Exception ignore) { }
		return m;
	}

	private Map<String, Boolean> drainByServer(MBeanServer dr, String admin) {
		Map<String, Boolean> m = new LinkedHashMap<>();
		try {
			for (ObjectName on : dr.queryNames(new ObjectName(DRAIN_PATTERN), null)) {
				m.put(serverOf(on, admin), Boolean.TRUE.equals(get(dr, on, "Drained")));
			}
		} catch (Exception ignore) { }
		return m;
	}

	static String adminName(MBeanServer dr) {
		String n = System.getProperty("weblogic.Name", "AdminServer");
		try {
			ObjectName dc = (ObjectName) dr.getAttribute(new ObjectName(DRS), "DomainConfiguration");
			Object an = dr.getAttribute(dc, "AdminServerName");
			if (an != null && !an.toString().isEmpty()) n = an.toString();
		} catch (Exception ignore) { }
		return n;
	}

	static String serverOf(ObjectName on, String admin) {
		String loc = on.getKeyProperty("Location");
		return (loc != null && !loc.isEmpty()) ? loc : admin;
	}

	static ObjectName obj(MBeanServer mbs, ObjectName on, String attr) {
		Object v = get(mbs, on, attr);
		return (v instanceof ObjectName) ? (ObjectName) v : null;
	}
	static String str(MBeanServer mbs, ObjectName on, String attr) {
		Object v = get(mbs, on, attr);
		return v == null ? null : v.toString();
	}
	static long lng(MBeanServer mbs, ObjectName on, String attr) {
		Object v = get(mbs, on, attr);
		return v instanceof Number ? ((Number) v).longValue() : -1;
	}
	static Object get(MBeanServer mbs, ObjectName on, String attr) {
		try { return mbs.getAttribute(on, attr); } catch (Exception e) { return null; }
	}
	private static String num(long v) { return v < 0 ? "null" : Long.toString(v); }

	private static String esc(String s) {
		if (s == null) return "";
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch == '"' || ch == '\\') b.append('\\').append(ch);
			else if (ch < 0x20) b.append(' ');
			else b.append(ch);
		}
		return b.toString();
	}

	static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException { super(); }
	}
}
