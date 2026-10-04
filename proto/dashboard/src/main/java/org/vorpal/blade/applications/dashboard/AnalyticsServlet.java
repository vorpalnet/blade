package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.databind.ObjectMapper;

/// The dashboard's analytics data path: named reports from [AnalyticsReports],
/// written as JSON.
///
/// URL: `/blade/dashboard/data?r=<report>[,<report>…][&days=N][&sample=include][&cluster=X][&fresh=1]`
///
/// The response is one object keyed by report name; each value is
/// `{columns, rows, asOf}` or `{error}`. Several reports per request let the
/// overview fill every card in one round trip.
///
/// **Cached for five minutes per report and scope.** The overview polls every
/// minute on every open browser; the database sees each query at most once per
/// five minutes per node. `fresh=1` (the detail pages) bypasses the cache and
/// refills it. `asOf` says when the numbers were read, so a card can show its age.
///
/// No JAX-RS: a plain servlet, as the rest of this application.
@WebServlet("/data")
public class AnalyticsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(AnalyticsServlet.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static final long TTL_MS = 5 * 60_000L;
	static final int MAX_DAYS = 400;

	/// Report results by [AnalyticsReports.Filter#key]. Per node, per application
	/// instance; nothing is shared across the cluster.
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	private static final class Cached {
		final AnalyticsReports.Table table;
		final long asOf;

		Cached(AnalyticsReports.Table table, long asOf) {
			this.table = table;
			this.asOf = asOf;
		}
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.setHeader("Cache-Control", "no-store");

		String r = req.getParameter("r");
		if (r == null || r.trim().isEmpty()) {
			resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			MAPPER.writeValue(resp.getWriter(), Map.of("error", "name at least one report: r=" + AnalyticsReports.NAMES));
			return;
		}

		DashboardSettings settings = settings();
		int days = clamp(intParam(req, "days", settings == null ? 30 : settings.getHistoryDays()), 1, MAX_DAYS);
		boolean includeSample = "include".equals(req.getParameter("sample"));
		boolean fresh = "1".equals(req.getParameter("fresh"));
		AnalyticsReports.Filter filter = new AnalyticsReports.Filter(days, includeSample, req.getParameter("cluster"),
				new Date());

		Map<String, Object> out = new LinkedHashMap<>();
		AnalyticsReports reports = null;
		String unavailable = null;
		for (String raw : r.split(",")) {
			String name = raw.trim();
			if (!AnalyticsReports.NAMES.contains(name)) {
				out.put(name, Map.of("error", "unknown report"));
				continue;
			}
			String key = filter.key(name);
			Cached hit = fresh ? null : cache.get(key);
			if (hit == null || System.currentTimeMillis() - hit.asOf > TTL_MS) {
				if (reports == null && unavailable == null) {
					try {
						reports = store().reports(dataSource(settings));
					} catch (RuntimeException e) {
						unavailable = "analytics database unavailable: " + rootMessage(e);
						logger.log(Level.WARNING, "dashboard " + unavailable, e);
					}
				}
				if (unavailable != null) {
					out.put(name, Map.of("error", unavailable));
					continue;
				}
				try {
					hit = new Cached(reports.run(name, filter), System.currentTimeMillis());
					cache.put(key, hit);
				} catch (RuntimeException e) {
					logger.log(Level.WARNING, "dashboard report " + name + " failed", e);
					out.put(name, Map.of("error", rootMessage(e)));
					continue;
				}
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("columns", hit.table.columns);
			result.put("rows", hit.table.rows);
			result.put("asOf", hit.asOf);
			out.put(name, result);
		}
		prune();
		MAPPER.writeValue(resp.getWriter(), out);
	}

	/// Drops expired entries, so odd `days` values and retired clusters do not
	/// accumulate for the life of the application.
	private void prune() {
		long cutoff = System.currentTimeMillis() - TTL_MS;
		for (Iterator<Cached> i = cache.values().iterator(); i.hasNext();) {
			if (i.next().asOf < cutoff) {
				i.remove();
			}
		}
	}

	@SuppressWarnings("unchecked")
	private DashboardSettings settings() {
		Object sm = getServletContext().getAttribute(DashboardSettingsStartup.SETTINGS_ATTR);
		return (sm instanceof SettingsManager) ? ((SettingsManager<DashboardSettings>) sm).getCurrent() : null;
	}

	private AnalyticsStore store() {
		return (AnalyticsStore) getServletContext().getAttribute(AnalyticsStore.ATTR);
	}

	private static String dataSource(DashboardSettings settings) {
		return settings == null ? "jdbc/BladeAnalytics" : settings.getAnalyticsDataSource();
	}

	/// The innermost cause's message: JPA wraps a missing view or a refused
	/// connection several layers deep, and the outer messages say only "failed".
	private static String rootMessage(Throwable t) {
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String m = root.getMessage();
		return root.getClass().getSimpleName() + (m == null ? "" : ": " + m.trim());
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
}
