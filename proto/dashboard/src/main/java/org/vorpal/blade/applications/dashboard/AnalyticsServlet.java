package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

/// The dashboard's analytics data path. No JAX-RS: a plain servlet looks up the
/// `jdbc/BladeAnalytics` datasource, runs one aggregation against the reporting
/// VIEWS (never the raw tables), and writes the rows out as JSON for the chart
/// JavaScript to draw.
///
/// The queries are Oracle SQL because the ashburn analytics store is an Oracle
/// Autonomous DB. They read `v_calls`, the CDR view, so they survive the
/// underlying table shape changing.
///
/// URL: `/blade/dashboard/data?q=&lt;chart&gt;[&days=N]`.
@WebServlet("/data")
public class AnalyticsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(AnalyticsServlet.class.getName());
	private static final String DS_JNDI = "jdbc/BladeAnalytics";

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");

		String q = req.getParameter("q");
		int days = clamp(intParam(req, "days", 30), 1, 400);

		try (Connection c = dataSource().getConnection(); PrintWriter out = resp.getWriter()) {
			switch (q == null ? "" : q) {
			case "stats":
				stats(c, out);
				break;
			case "calls-per-day":
				pairs(c, out, days,
						"SELECT TO_CHAR(TRUNC(started_at),'YYYY-MM-DD') d, COUNT(*) n "
						+ "FROM v_calls WHERE started_at >= SYSDATE - ? "
						+ "GROUP BY TRUNC(started_at) ORDER BY TRUNC(started_at)");
				break;
			case "calls-by-app":
				pairs(c, out, days,
						"SELECT NVL(application,'—') a, COUNT(*) n "
						+ "FROM v_calls WHERE started_at >= SYSDATE - ? "
						+ "GROUP BY NVL(application,'—') ORDER BY COUNT(*) DESC FETCH FIRST 12 ROWS ONLY");
				break;
			case "avg-duration":
				pairs(c, out, days,
						"SELECT TO_CHAR(TRUNC(started_at),'YYYY-MM-DD') d, ROUND(AVG(duration_seconds),1) s "
						+ "FROM v_calls WHERE started_at >= SYSDATE - ? AND duration_seconds IS NOT NULL "
						+ "GROUP BY TRUNC(started_at) ORDER BY TRUNC(started_at)");
				break;
			default:
				resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				out.write("{\"error\":\"unknown chart '" + esc(q) + "'\"}");
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "dashboard analytics query failed for q=" + q, e);
			resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			resp.getWriter().write("{\"error\":\"" + esc(e.getClass().getSimpleName() + ": " + e.getMessage()) + "\"}");
		}
	}

	/// One aggregation → a JSON array of [label, number] pairs.
	private void pairs(Connection c, PrintWriter out, int days, String sql) throws Exception {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setInt(1, days);
			try (ResultSet rs = ps.executeQuery()) {
				out.write('[');
				boolean first = true;
				while (rs.next()) {
					if (!first) out.write(',');
					first = false;
					out.write("[\"" + esc(rs.getString(1)) + "\"," + numOrNull(rs.getString(2)) + ']');
				}
				out.write(']');
			}
		}
	}

	/// The headline tiles, in one round trip.
	private void stats(Connection c, PrintWriter out) throws Exception {
		String sql = "SELECT "
				+ "(SELECT COUNT(*) FROM v_calls WHERE started_at >= TRUNC(SYSDATE)) calls_today, "
				+ "(SELECT COUNT(*) FROM v_calls WHERE ended_at IS NULL) active_calls, "
				+ "(SELECT ROUND(AVG(duration_seconds),1) FROM v_calls WHERE started_at >= SYSDATE-1 AND duration_seconds IS NOT NULL) avg_dur, "
				+ "(SELECT COUNT(DISTINCT application) FROM v_calls WHERE started_at >= SYSDATE-7) apps "
				+ "FROM dual";
		try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
			rs.next();
			out.write("{\"callsToday\":" + numOrNull(rs.getString("calls_today"))
					+ ",\"activeCalls\":" + numOrNull(rs.getString("active_calls"))
					+ ",\"avgDurationSec\":" + numOrNull(rs.getString("avg_dur"))
					+ ",\"apps\":" + numOrNull(rs.getString("apps")) + '}');
		}
	}

	private DataSource dataSource() throws Exception {
		return (DataSource) new InitialContext().lookup(DS_JNDI);
	}

	private static int intParam(HttpServletRequest req, String name, int dflt) {
		try {
			String v = req.getParameter(name);
			return v == null ? dflt : Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return dflt;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static String numOrNull(String s) {
		return (s == null) ? "null" : s;
	}

	private static String esc(String s) {
		if (s == null) return "";
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			switch (ch) {
			case '"': b.append("\\\""); break;
			case '\\': b.append("\\\\"); break;
			case '\n': b.append("\\n"); break;
			case '\r': b.append("\\r"); break;
			case '\t': b.append("\\t"); break;
			default:
				if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
				else b.append(ch);
			}
		}
		return b.toString();
	}
}
