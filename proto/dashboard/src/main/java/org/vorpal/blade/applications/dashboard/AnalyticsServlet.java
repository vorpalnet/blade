package org.vorpal.blade.applications.dashboard;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

import com.fasterxml.jackson.databind.ObjectMapper;

/// The dashboard's analytics data path: named reports from [AnalyticsReports],
/// written as JSON.
///
/// URL: `/blade/dashboard/data?r=<report>[,<report>…][&days=N][&sample=include][&domain=X][&account=A][&tz=Zone][&fresh=1]`
///
/// The window is `days` back from now, `days=0` being the last hour (the
/// board's Live), or `from` and `until` in epoch millis: a custom range, or a
/// slice of a chart. The board narrows a panel further with `server`,
/// `outcome`, `app` and `cause`, and with `dow` (Monday 1) and `hod` for a cell of its
/// weekday-by-hour grid ([AnalyticsReports.Filter]).
///
/// `tz` is the viewer's IANA time zone (`America/Chicago`): days and hours are
/// cut, and times printed, in it. The data is UTC; without `tz`, or with one
/// this JVM does not know, the reports are in UTC.
///
/// The response is one object keyed by report name; each value is
/// `{columns, rows, asOf}` or `{error}`. Several reports per request let the
/// overview fill every card in one round trip.
///
/// **Cached for five minutes per report and scope.** The overview polls every
/// minute on every open browser; the database sees each query at most once per
/// five minutes per node. `fresh=1` (the detail pages) bypasses the cache and
/// refills it. `asOf` says when the numbers were read, so a card can show its age.
/// The live reports ([#LIVE]) are the exception: cached for seconds.
///
/// No JAX-RS: a plain servlet, as the rest of this application.
@WebServlet("/data")
public class AnalyticsServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(AnalyticsServlet.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static final long TTL_MS = 5 * 60_000L;
	/// Reports the board polls on its live cycle, cached for seconds: still one
	/// query per few seconds per node however many boards are open.
	static final java.util.Set<String> LIVE = java.util.Collections.singleton("calls.now");
	static final long LIVE_TTL_MS = 4_000L;
	/// How long a report over the last hour (`days=0`, the board's Live) is
	/// reused: its numbers move by the minute.
	static final long LIVE_RANGE_TTL_MS = 15_000L;
	static final int MAX_DAYS = DashboardSettings.MAX_RANGE_DAYS;

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
		int days = clamp(intParam(req, "days", settings == null ? 7 : settings.getDefaultDays()), 0, MAX_DAYS);
		boolean includeSample = "include".equals(req.getParameter("sample"));
		boolean fresh = "1".equals(req.getParameter("fresh"));
		Date now = new Date();
		AnalyticsReports.Filter filter = new AnalyticsReports.Filter(days, includeSample, req.getParameter("domain"),
				now, zone(req.getParameter("tz")));
		Long from = longParam(req, "from"), until = longParam(req, "until");
		if (from != null) {
			// A chosen window ends no later than now and spans at most MAX_DAYS.
			long end = until == null ? now.getTime() : Math.min(until, now.getTime());
			long start = Math.max(Math.min(from, end - 60_000L), end - MAX_DAYS * 86_400_000L);
			filter = filter.window(new Date(start), new Date(end));
		}
		if (settings != null) {
			filter = filter.tuned(settings.getLeakHours() * 3_600_000L, settings.getSplitCallSeconds() * 1000L,
					settings.getSampleFreshMinutes() * 60_000L);
		}
		long ttl = ttl(settings);
		Access access = Access.forRequest(req, settings);
		if (access != null) {
			filter = filter.limitedTo(access);
		}
		filter = filter.forAccount(req.getParameter("account")).onServer(req.getParameter("server"))
				.forCalls(req.getParameter("outcome"), req.getParameter("app"))
				.forCause(req.getParameter("cause"))
				.atHour(longParam(req, "dow") == null ? null : longParam(req, "dow").intValue(),
						longParam(req, "hod") == null ? null : longParam(req, "hod").intValue());
		long reuse = (days == 0 && from == null) ? Math.min(ttl, LIVE_RANGE_TTL_MS) : ttl;

		Map<String, Object> out = new LinkedHashMap<>();
		List<String> shown = new ArrayList<>(), refused = new ArrayList<>();
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
			if (hit == null || System.currentTimeMillis() - hit.asOf > (LIVE.contains(name) ? Math.min(ttl, LIVE_TTL_MS) : reuse)) {
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
				} catch (SecurityException e) {
					refused.add(name);
					out.put(name, Map.of("error", e.getMessage()));
					continue;
				} catch (RuntimeException e) {
					logger.log(Level.WARNING, "dashboard report " + name + " failed", e);
					out.put(name, Map.of("error", rootMessage(e)));
					continue;
				}
			}
			if (AnalyticsReports.CONTENT.contains(name) || filter.account != null) {
				shown.add(name);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("columns", hit.table.columns);
			result.put("rows", hit.table.rows);
			result.put("asOf", hit.asOf);
			out.put(name, result);
		}
		prune(ttl);
		audit(req, filter, shown, refused);
		MAPPER.writeValue(resp.getWriter(), out);
	}

	/// The audit record: who saw individual calls or what was said, or was
	/// refused them. Counts, durations and bands are not recorded, so a board
	/// left open does not write a row a minute; anything that identifies a
	/// call or a caller is.
	private static void audit(HttpServletRequest req, AnalyticsReports.Filter filter, List<String> shown,
			List<String> refused) {
		if (shown.isEmpty() && refused.isEmpty()) {
			return;
		}
		Events.publish((Long) null, null, BladeEventTypes.DASHBOARD_VIEWED, data -> data
				.put("viewer", req.getRemoteUser() == null ? "(anonymous)" : req.getRemoteUser())
				.put("reports", String.join(",", shown))
				.put("days", filter.days)
				.put("forDomain", filter.domain)
				.put("access", filter.access == null ? "everything (no grants configured)" : filter.access.key())
				.put("refused", refused.isEmpty() ? null : String.join(",", refused))
				.put("account", filter.account));
	}

	/// Drops expired entries, so odd `days` values and retired domains do not
	/// accumulate for the life of the application.
	private void prune(long ttl) {
		long cutoff = System.currentTimeMillis() - ttl;
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

	/// How long a result is reused: the settings' `cacheMinutes`, else [#TTL_MS].
	private static long ttl(DashboardSettings settings) {
		return settings == null ? TTL_MS : settings.getCacheMinutes() * 60_000L;
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

	private static Long longParam(HttpServletRequest req, String name) {
		try {
			String v = req.getParameter(name);
			return v == null || v.trim().isEmpty() ? null : Long.valueOf(v.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	/// The viewer's zone, or null (UTC) for none or one this JVM does not know.
	static ZoneId zone(String tz) {
		if (tz == null || tz.isEmpty()) {
			return null;
		}
		try {
			return ZoneId.of(tz);
		} catch (DateTimeException unknown) {
			return null;
		}
	}
}
