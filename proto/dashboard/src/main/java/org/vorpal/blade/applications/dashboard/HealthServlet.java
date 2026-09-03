package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.io.PrintWriter;
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
/// row per server: node state, JVM heap, thread pool, JDBC pools, plus OCCAS's
/// `SipServerRuntime` session counts and throughput and the BLADE drain flag.
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
	private static final String DRS = "com.bea:Name=DomainRuntimeService,Type=weblogic.management.mbeanservers.domainruntime.DomainRuntimeServiceMBean";
	private static final String SIP_PATTERN = "com.bea:Type=SipServerRuntime,*";
	private static final String DRAIN_PATTERN = "vorpal.blade:Name=*,Type=Drain,*";

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
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
		out.write("{\"nodes\":[");
		boolean first = true;
		for (ObjectName sr : servers) {
			String name = str(dr, sr, "Name");
			if (name == null) name = "?";
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

			ObjectName tp = obj(dr, sr, "ThreadPoolRuntime");
			out.write(",\"threadsTotal\":" + num(tp == null ? -1 : lng(dr, tp, "ExecuteThreadTotalCount")));
			out.write(",\"threadsStuck\":" + num(tp == null ? -1 : lng(dr, tp, "StuckThreadCount")));
			out.write(",\"threadsPending\":" + num(tp == null ? -1 : lng(dr, tp, "PendingUserRequestCount")));

			out.write(",\"jdbc\":");
			jdbc(dr, obj(dr, sr, "JDBCServiceRuntime"), out);

			long[] s = sip.get(name);
			out.write(",\"appSessions\":" + (s == null ? "null" : num(s[0])));
			out.write(",\"sipSessions\":" + (s == null ? "null" : num(s[1])));
			out.write(",\"sipThroughput\":" + (s == null ? "null" : num(s[2])));

			Boolean d = drained.get(name);
			out.write(",\"drained\":" + (d == null ? "false" : d.toString()) + '}');
		}
		out.write("],\"asOf\":" + System.currentTimeMillis() + '}');
	}

	private void jdbc(MBeanServer dr, ObjectName svc, PrintWriter out) {
		out.write('[');
		if (svc != null) {
			try {
				ObjectName[] dss = (ObjectName[]) dr.getAttribute(svc, "JDBCDataSourceRuntimeMBeans");
				boolean first = true;
				if (dss != null) for (ObjectName ds : dss) {
					if (!first) out.write(',');
					first = false;
					out.write("{\"name\":\"" + esc(str(dr, ds, "Name")) + "\",\"active\":" + num(lng(dr, ds, "ActiveConnectionsCurrentCount"))
							+ ",\"capacity\":" + num(lng(dr, ds, "CurrCapacity"))
							+ ",\"waiting\":" + num(lng(dr, ds, "WaitingForConnectionCurrentCount")) + '}');
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

	private static String adminName(MBeanServer dr) {
		String n = System.getProperty("weblogic.Name", "AdminServer");
		try {
			ObjectName dc = (ObjectName) dr.getAttribute(new ObjectName(DRS), "DomainConfiguration");
			Object an = dr.getAttribute(dc, "AdminServerName");
			if (an != null && !an.toString().isEmpty()) n = an.toString();
		} catch (Exception ignore) { }
		return n;
	}

	private static String serverOf(ObjectName on, String admin) {
		String loc = on.getKeyProperty("Location");
		return (loc != null && !loc.isEmpty()) ? loc : admin;
	}

	private static ObjectName obj(MBeanServer mbs, ObjectName on, String attr) {
		Object v = get(mbs, on, attr);
		return (v instanceof ObjectName) ? (ObjectName) v : null;
	}
	private static String str(MBeanServer mbs, ObjectName on, String attr) {
		Object v = get(mbs, on, attr);
		return v == null ? null : v.toString();
	}
	private static long lng(MBeanServer mbs, ObjectName on, String attr) {
		Object v = get(mbs, on, attr);
		return v instanceof Number ? ((Number) v).longValue() : -1;
	}
	private static Object get(MBeanServer mbs, ObjectName on, String attr) {
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

	private static class CloseableContext extends InitialContext implements AutoCloseable {
		CloseableContext() throws javax.naming.NamingException { super(); }
	}
}
