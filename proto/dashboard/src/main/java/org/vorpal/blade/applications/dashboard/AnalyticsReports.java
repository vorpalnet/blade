package org.vorpal.blade.applications.dashboard;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.TypedQuery;

import org.vorpal.blade.applications.dashboard.model.UtcTimestamp;
import org.vorpal.blade.framework.v3.net.CallOrigin;
import org.vorpal.blade.framework.v3.net.Legs;

/// Every chart and table the dashboard draws, as a JPQL query over the
/// reporting views ([org.vorpal.blade.applications.dashboard.model]).
///
/// **Portable JPQL, finished in Java where JPQL stops.** The same queries run
/// on Oracle, MySQL and SQL Server because EclipseLink writes the SQL. JPA 2.2
/// has no portable date bucketing and no median, so:
///
/// - Day and hour buckets are cut in the viewer's time zone ([Filter#zone]),
///   here, from rows the database groups by UTC quarter-hour on EclipseLink's
///   `EXTRACT(part FROM date)` ([#quarters]). The database stores UTC and
///   knows nothing of the viewer; every zone in use is a whole number of
///   quarter-hours from UTC, so each quarter falls inside one local hour and
///   one local day. The weekday is computed here too, because every database
///   numbers weekdays differently.
/// - Medians are taken here from the sorted durations of the window.
/// - Peak concurrency is swept here from start and end times.
///
/// **Sample rows are excluded unless asked for.** The analytics console's
/// sample-data generator writes synthetic calls under `cluster_name = 'sample'`.
/// Left in, they outnumber a lab's real traffic many times over, so every query
/// drops them by default ([Filter#includeSample]).
///
/// **A call is one row, whichever applications it reaches.** Every application
/// on a call reports the same correlator, so the analytics service keeps one
/// `sessions` row per call, named for the application it heard from first and
/// ended by the last to stop. Per-application counts therefore come from
/// `v_events`, which records who published each fact, and never from the
/// application column of `v_calls`.
///
/// **There is no pooled answer rate.** Some applications publish `callStarted`
/// but never `callAnswered` (a conference bridge, for one), so a rate pooled
/// across applications reports their calls as unanswered. Answer rate exists
/// only per application ([#outcomesByApplication]).
///
/// Every report returns a [Table]: column names and rows. The servlet writes it
/// as JSON unchanged.
public class AnalyticsReports {

	/// The scope every report is asked for.
	public static final class Filter {
		final int days;
		final boolean includeSample;
		final String domain;
		final Date now;
		/// The viewer's zone: days and hours are cut, and times printed, in it.
		final ZoneId zone;
		/// Thresholds from the dashboard's settings: [#LEAK_MS], [#SPLIT_MS] and
		/// [#SAMPLE_FRESH_MS] unless [#tuned] changes them.
		final long leakMs;
		final long splitMs;
		final long freshMs;
		/// What the viewer may see; null for everything (no grants configured).
		final Access access;
		/// One caller's calls only: their `account` key; null for every caller.
		final String account;

		/// @param days          how far back the window reaches
		/// @param includeSample whether `cluster_name = 'sample'` rows count
		/// @param domain        only this domain (`cluster_name`), or null for all
		/// @param now           the end of the window (a parameter so tests can pin it)
		/// @param zone          the viewer's time zone, or null for UTC
		public Filter(int days, boolean includeSample, String domain, Date now, ZoneId zone) {
			this.days = days;
			this.includeSample = includeSample;
			this.domain = (domain == null || domain.isEmpty()) ? null : domain;
			this.now = now;
			this.zone = (zone == null) ? ZoneOffset.UTC : zone;
			this.leakMs = LEAK_MS;
			this.splitMs = SPLIT_MS;
			this.freshMs = SAMPLE_FRESH_MS;
			this.access = null;
			this.account = null;
		}

		private Filter(Filter f, String domain, long leakMs, long splitMs, long freshMs, Access access,
				String account) {
			this.days = f.days;
			this.includeSample = f.includeSample;
			this.domain = domain;
			this.now = f.now;
			this.zone = f.zone;
			this.leakMs = leakMs;
			this.splitMs = splitMs;
			this.freshMs = freshMs;
			this.access = access;
			this.account = account;
		}

		/// This scope with the settings' thresholds in place of the defaults.
		public Filter tuned(long leakMs, long splitMs, long freshMs) {
			return new Filter(this, domain, leakMs, splitMs, freshMs, access, account);
		}

		/// This scope limited to what `access` lets the viewer see.
		public Filter limitedTo(Access access) {
			return new Filter(this, domain, leakMs, splitMs, freshMs, access, account);
		}

		/// This scope narrowed to one caller: the calls filed under `account`
		/// ([KeyRow]). Seeing it needs a grant with content, and a department
		/// sees the account only on the calls it took part in.
		public Filter forAccount(String account) {
			return new Filter(this, domain, leakMs, splitMs, freshMs, access,
					(account == null || account.isEmpty()) ? null : account);
		}

		/// This scope without its domain.
		Filter allDomains() {
			return new Filter(this, null, leakMs, splitMs, freshMs, access, account);
		}

		/// Where the window opens. One day is the last 24 hours, cut into hours.
		/// Longer windows are whole days in the viewer's zone, today's included:
		/// seven days is today and the six before it. A rolling 168 hours would
		/// open part-way through a day, so the first day's bar would read low and
		/// the weekday grid would add that day's afternoon to today's.
		Date since() {
			if (days <= 1) {
				return new Date(now.getTime() - days * 86_400_000L);
			}
			return Date.from(LocalDate.ofInstant(now.toInstant(), zone).minusDays(days - 1L).atStartOfDay(zone)
					.toInstant());
		}

		/// The cache key for this scope plus a report name.
		String key(String report) {
			return report + '|' + days + '|' + includeSample + '|' + (domain == null ? "" : domain) + '|' + zone + '|'
					+ leakMs + '|' + splitMs + '|' + freshMs + '|' + (access == null ? "" : access.key()) + '|'
					+ (account == null ? "" : account);
		}
	}

	/// A report's result: named columns and rows of values (strings, numbers, nulls).
	public static final class Table {
		public final List<String> columns;
		public final List<List<Object>> rows = new ArrayList<>();

		Table(String... columns) {
			this.columns = Arrays.asList(columns);
		}

		Table row(Object... values) {
			rows.add(Arrays.asList(values));
			return this;
		}
	}

	/// Reports that show individual calls or what was said in them: only a
	/// grant with `content` sees them (see [Access#seesContent]).
	static final Set<String> CONTENT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("highrisk.calls",
			"sessions.open", "conversation.intents", "health.split-calls", "risk.verdicts")));

	/// The report names the servlet accepts, in the order a reader meets them.
	public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
			"calls.summary", "calls.daily", "calls.hourly", "calls.hour-of-day", "calls.weekday", "calls.heatmap",
			"calls.by-application", "calls.by-engine", "calls.by-tenant", "calls.concurrent", "calls.now", "calls.by-outcome",
			"calls.by-area",
			"calls.domains",
			"duration.summary", "duration.bands", "duration.daily", "duration.by-application",
			"outcomes.summary", "outcomes.daily", "outcomes.ended", "outcomes.by-application",
			"sessions.summary", "sessions.open", "sessions.by-application",
			"platform.summary", "platform.daily", "platform.by-application", "platform.versions",
			"events.summary", "events.daily", "events.by-type", "events.by-type-application",
			"risk.summary", "risk.daily", "risk.by-band", "risk.by-trigger", "risk.signals",
			"highrisk.by-band", "highrisk.calls",
			"conversation.summary", "conversation.daily", "conversation.by-intent", "conversation.by-addressed",
			"conversation.intents",
			"health.summary", "health.coverage", "health.chains", "health.split-calls", "health.publishing",
			"health.publishing-daily", "platform.servers", "platform.latest", "platform.servers-hourly", "platform.heap", "platform.cpu",
			"metrics.summary",
			"metrics.counters", "metrics.timings", "risk.verdicts", "risk.calibration"));

	// Lifecycle names as the views carry them: the short names, plus the camelCase
	// and dotted forms older writers used. Compared lower-cased.
	static final List<String> STARTED = Arrays.asList("callstarted", "call.started", "started");
	/// Connected counts as answered, in every report, so every answer count
	/// agrees.
	static final List<String> ANSWERED = Arrays.asList("callanswered", "call.answered", "answered", "callconnected",
			"call.connected", "connected");
	static final List<String> COMPLETED = Arrays.asList("callcompleted", "call.completed", "completed");
	static final List<String> LOST = Arrays.asList("calldeclined", "call.declined", "declined", "callabandoned",
			"call.abandoned", "abandoned", "calldropped", "call.dropped", "dropped");
	/// The callee refused the call.
	static final List<String> DECLINED = Arrays.asList("calldeclined", "call.declined", "declined");
	/// The caller hung up before anyone answered.
	static final List<String> ABANDONED = Arrays.asList("callabandoned", "call.abandoned", "abandoned");
	/// The call expired with its legs in no state an ordinary ending leaves:
	/// its signaling was dropped (the framework's `DroppedCalls`).
	static final List<String> DROPPED = Arrays.asList("calldropped", "call.dropped", "dropped");
	/// A leg a server refused, with its status and who answered.
	static final String BRANCH_FAILED = "branchFailed";
	/// A proxy's one call event: it chose where the call goes, then left it.
	static final List<String> ROUTED = Arrays.asList("callrouted", "call.routed", "routed");

	/// Band order: worst first, top to bottom, so position carries the ranking as
	/// well as the word and the colour do.
	static final List<String> BANDS = Arrays.asList("SUSPECT", "WATCH", "CLEAR");

	private static final String NONE = "(none)";
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

	/// Two call rows with one Vorpal-ID born this close together are one call
	/// split in two, not the ID reused: see [#healthSplitCalls]. The default;
	/// the settings' `splitCallSeconds` replaces it.
	static final long SPLIT_MS = 60_000L;

	/// A session still open after this long is a leak, not a call: it is listed
	/// by `sessions.open` but kept out of peak concurrency. The default; the
	/// settings' `leakHours` replaces it.
	static final long LEAK_MS = 24 * 3_600_000L;

	private final EntityManagerFactory factory;

	public AnalyticsReports(EntityManagerFactory factory) {
		this.factory = factory;
	}

	/// Runs one named report.
	///
	/// @throws IllegalArgumentException for a name not in [#NAMES]
	public Table run(String name, Filter f) {
		if (f.access != null && !f.access.seesContent() && (f.account != null || CONTENT.contains(name))) {
			throw new SecurityException("seeing individual calls and what was said needs a grant with content");
		}
		EntityManager em = factory.createEntityManager();
		try {
			switch (name) {
			case "calls.summary": return callsSummary(em, f);
			case "calls.now": return callsNow(em, f);
			case "calls.by-outcome": return callsByOutcome(em, f);
			case "calls.by-area": return callsByArea(em, f);
			case "calls.daily": return callsDaily(em, f);
			case "calls.hourly": return callsHourly(em, f);
			case "calls.hour-of-day": return callsHourOfDay(em, f);
			case "calls.weekday": return callsWeekday(em, f);
			case "calls.heatmap": return callsHeatmap(em, f);
			case "calls.by-application": return callsByApplication(em, f);
			case "calls.by-engine": return countBy(em, f, "c.server", "engine");
			case "calls.by-tenant": return countBy(em, f, "c.tenant", "tenant");
			case "calls.concurrent": return callsConcurrent(em, f);
			case "calls.domains": return callsDomains(em, f);
			case "duration.summary": return durationSummary(em, f);
			case "duration.bands": return durationBands(em, f);
			case "duration.daily": return durationDaily(em, f);
			case "duration.by-application": return durationByApplication(em, f);
			case "outcomes.summary": return outcomesSummary(em, f);
			case "outcomes.daily": return outcomesDaily(em, f);
			case "outcomes.ended": return outcomesEnded(em, f);
			case "outcomes.by-application": return outcomesByApplication(em, f);
			case "sessions.summary": return sessionsSummary(em, f);
			case "sessions.open": return sessionsOpen(em, f);
			case "sessions.by-application": return sessionsByApplication(em, f);
			case "platform.summary": return platformSummary(em, f);
			case "platform.daily": return platformDaily(em, f);
			case "platform.by-application": return platformByApplication(em, f);
			case "platform.versions": return platformVersions(em, f);
			case "events.summary": return eventsSummary(em, f);
			case "events.daily": return eventsDaily(em, f);
			case "events.by-type": return eventsByType(em, f);
			case "events.by-type-application": return eventsByTypeApplication(em, f);
			case "risk.summary": return riskSummary(em, f);
			case "risk.daily": return riskDaily(em, f);
			case "risk.by-band": return riskByBand(em, f);
			case "risk.by-trigger": return riskByTrigger(em, f);
			case "risk.signals": return riskSignals(em, f);
			case "highrisk.by-band": return highriskByBand(em, f);
			case "highrisk.calls": return highriskCalls(em, f);
			case "conversation.summary": return conversationSummary(em, f);
			case "conversation.daily": return conversationDaily(em, f);
			case "conversation.by-intent": return conversationBy(em, f, "v.intent", "intent");
			case "conversation.by-addressed": return conversationBy(em, f, "v.addressed", "addressed");
			case "conversation.intents": return conversationIntents(em, f);
			case "health.summary": return healthSummary(em, f);
			case "health.coverage": return healthCoverage(em, f);
			case "health.chains": return healthChains(em, f);
			case "health.split-calls": return healthSplitCalls(em, f);
			case "health.publishing": return healthPublishing(em, f);
			case "health.publishing-daily": return healthPublishingDaily(em, f);
			case "platform.servers": return platformServers(em, f);
			case "platform.latest": return platformLatest(em, f);
			case "platform.servers-hourly": return platformServersHourly(em, f);
			case "platform.heap": return platformPeak(em, f, "heapUsedPct", "peak heap %");
			case "platform.cpu": return platformPeak(em, f, "cpuPct", "peak cpu %");
			case "metrics.summary": return metricsSummary(em, f);
			case "metrics.counters": return metricsCounters(em, f);
			case "metrics.timings": return metricsTimings(em, f);
			case "risk.verdicts": return riskVerdicts(em, f);
			case "risk.calibration": return riskCalibration(em, f);
			default: throw new IllegalArgumentException("unknown report '" + name + "'");
			}
		} finally {
			em.close();
		}
	}

	// ── calls ────────────────────────────────────────────────────────────────

	private Table callsSummary(EntityManager em, Filter f) {
		long calls = single(em, "SELECT COUNT(c) FROM CallRow c" + where("c", "c.startedAt", f), f, true);
		long callsToday = singleSince(em, "SELECT COUNT(c) FROM CallRow c" + where("c", "c.startedAt", f), f,
				startOfDay(f));
		// Open is up now: a session open past LEAK_MS is a leak, whenever it
		// started, and is counted apart rather than as a call in progress.
		long unclosed = single(em, "SELECT COUNT(c) FROM CallRow c" + where("c", null, f) + " AND c.endedAt IS NULL",
				f, false);
		long open = singleSince(em, "SELECT COUNT(c) FROM CallRow c" + where("c", "c.startedAt", f)
				+ " AND c.endedAt IS NULL", f, new Date(f.now.getTime() - f.leakMs));
		// Not COUNT(DISTINCT c.application): a call row names only the first
		// application the analytics service heard from (see #callsByApplication).
		long apps = callsPerHandler(em, f).size();
		return new Table("calls", "today", "open", "leaked", "applications").row(calls, callsToday, open,
				unclosed - open, apps);
	}

	private Table callsDaily(EntityManager em, Filter f) {
		Map<String, Long> byDay = dayCounts(em, "c.startedAt", "COUNT(c)", "CallRow c", "c", f, null);
		Table t = new Table("day", "calls");
		for (Map.Entry<String, Long> e : fillDays(byDay, f).entrySet()) {
			t.row(e.getKey(), e.getValue());
		}
		return t;
	}

	private Table callsHourly(EntityManager em, Filter f) {
		Map<LocalDateTime, Long> byHour = hourCounts(em, "c.startedAt", "COUNT(c)", "CallRow c", "c", f);
		Table t = new Table("hour", "calls");
		LocalDateTime h = LocalDateTime.ofInstant(f.since().toInstant(), f.zone).withMinute(0).withSecond(0).withNano(0);
		LocalDateTime end = LocalDateTime.ofInstant(f.now.toInstant(), f.zone);
		for (; !h.isAfter(end); h = h.plusHours(1)) {
			t.row(h.format(HOUR), byHour.getOrDefault(h, 0L));
		}
		return t;
	}

	private Table callsHourOfDay(EntityManager em, Filter f) {
		long[] hours = new long[24];
		for (Map.Entry<LocalDateTime, Long> e : hourCounts(em, "c.startedAt", "COUNT(c)", "CallRow c", "c", f)
				.entrySet()) {
			hours[e.getKey().getHour()] += e.getValue();
		}
		Table t = new Table("hour", "calls");
		for (int h = 0; h < 24; h++) {
			t.row(h, hours[h]);
		}
		return t;
	}

	private Table callsWeekday(EntityManager em, Filter f) {
		long[] week = new long[7];
		for (Map.Entry<String, Long> e : dayCounts(em, "c.startedAt", "COUNT(c)", "CallRow c", "c", f, null)
				.entrySet()) {
			week[LocalDate.parse(e.getKey(), DAY).getDayOfWeek().getValue() - 1] += e.getValue();
		}
		Table t = new Table("weekday", "calls");
		for (DayOfWeek d : DayOfWeek.values()) {
			t.row(weekday(d), week[d.getValue() - 1]);
		}
		return t;
	}

	/// Weekday (Mon = 0) by hour of day, every cell present so the grid is whole.
	private Table callsHeatmap(EntityManager em, Filter f) {
		long[][] grid = new long[7][24];
		for (Map.Entry<LocalDateTime, Long> e : hourCounts(em, "c.startedAt", "COUNT(c)", "CallRow c", "c", f)
				.entrySet()) {
			grid[e.getKey().getDayOfWeek().getValue() - 1][e.getKey().getHour()] += e.getValue();
		}
		Table t = new Table("weekday", "hour", "calls");
		for (int d = 0; d < 7; d++) {
			for (int h = 0; h < 24; h++) {
				t.row(weekday(DayOfWeek.of(d + 1)), h, grid[d][h]);
			}
		}
		return t;
	}

	/// Calls each application took part in ([#callsPerHandler]). The counts can
	/// add up to more than the call total: that is a call reaching two
	/// applications.
	private Table callsByApplication(EntityManager em, Filter f) {
		Map<String, Long> byApp = callsPerHandler(em, f);
		List<String> apps = new ArrayList<>(byApp.keySet());
		apps.sort(Comparator.comparing((String a) -> -byApp.get(a)).thenComparing(a -> a));
		Table t = new Table("application", "calls");
		for (String app : apps) {
			t.row(app, byApp.get(app));
		}
		return t;
	}

	/// Calls that started in the window, per application that handled them.
	///
	/// **Not from the application column of `v_calls` alone.** A call is one
	/// `sessions` row however many applications it passes through, and that row
	/// names only the application the analytics service heard from first. An
	/// application handled a call if it published any event about it, or if
	/// the row names it.
	///
	/// **Counted in the database, by joins only.** Per application: the calls
	/// it published about, plus the calls its rows name, less those its rows
	/// name that it also published about (counted once already). A correlated
	/// `NOT EXISTS` says the same in one query, and on Oracle ADB it ran past
	/// 90 seconds over a month (rig, 2026-10-08): `v_events` is a view, and the
	/// subquery ran per call row.
	private Map<String, Long> callsPerHandler(EntityManager em, Filter f) {
		Map<String, Long> byApp = new HashMap<>();
		for (Object[] r : list(em, "SELECT e.application, COUNT(DISTINCT e.callId) FROM EventRow e, CallRow c"
				+ where("c", "c.startedAt", f, "e") + " AND c.callId = e.callId GROUP BY e.application", f, true)) {
			byApp.merge(label(r[0]), num(r[1]), Long::sum);
		}
		for (Object[] r : list(em, "SELECT c.application, COUNT(c) FROM CallRow c" + where("c", "c.startedAt", f)
				+ " GROUP BY c.application", f, true)) {
			byApp.merge(label(r[0]), num(r[1]), Long::sum);
		}
		for (Object[] r : list(em, "SELECT c.application, COUNT(DISTINCT c.callId) FROM CallRow c, EventRow e"
				+ where("c", "c.startedAt", f, "e") + " AND c.callId = e.callId AND e.application = c.application"
				+ " GROUP BY c.application", f, true)) {
			byApp.merge(label(r[0]), -num(r[1]), Long::sum);
		}
		return byApp;
	}

	/// The applications that handled each call that started in the window, by
	/// call id, as [#callsPerHandler] defines handling. Per call, so for the
	/// detail reports only: the summaries count in the database.
	private Map<String, TreeSet<String>> participation(EntityManager em, Filter f) {
		Map<String, TreeSet<String>> appsByCall = new HashMap<>();
		for (Object[] r : list(em, "SELECT c.callId, c.application FROM CallRow c" + where("c", "c.startedAt", f), f,
				true)) {
			appsByCall.computeIfAbsent((String) r[0], k -> new TreeSet<>()).add(label(r[1]));
		}
		for (Object[] r : list(em, "SELECT DISTINCT e.callId, e.application FROM EventRow e, CallRow c"
				+ where("c", "c.startedAt", f, "e") + " AND c.callId = e.callId", f, true)) {
			appsByCall.computeIfAbsent((String) r[0], k -> new TreeSet<>()).add(label(r[1]));
		}
		return appsByCall;
	}

	/// Calls that started in the window and reached more than one application:
	/// an application other than the one the row names published about them.
	/// A join, not a correlated subquery, for the reason [#callsPerHandler] gives.
	private long multiApplicationCalls(EntityManager em, Filter f) {
		return single(em, "SELECT COUNT(DISTINCT c.callId) FROM CallRow c, EventRow e" + where("c", "c.startedAt", f, "e")
				+ " AND c.callId = e.callId AND e.application <> c.application", f, true);
	}

	private Table countBy(EntityManager em, Filter f, String path, String column) {
		List<Object[]> rows = list(em, "SELECT " + path + ", COUNT(c) FROM CallRow c" + where("c", "c.startedAt", f)
				+ " GROUP BY " + path, f, true);
		Table t = new Table(column, "calls");
		rows.sort(byCountDesc(1));
		for (Object[] r : rows) {
			t.row(label(r[0]), num(r[1]));
		}
		return t;
	}

	/// The most calls up at once in each hour of the window.
	///
	/// A sweep over start and end times: +1 at each start, −1 at each end, the
	/// running sum's peak per hour. Calls already up when the window opens start
	/// the count; sessions open longer than [#LEAK_MS] are leaks, not calls, and
	/// would otherwise hold the count up forever.
	private Table callsConcurrent(EntityManager em, Filter f) {
		Date since = f.since();
		List<long[]> deltas = upDeltas(em, f, since);
		Table t = new Table("hour", "peak");
		LocalDateTime h = LocalDateTime.ofInstant(since.toInstant(), f.zone).withMinute(0).withSecond(0).withNano(0);
		LocalDateTime end = LocalDateTime.ofInstant(f.now.toInstant(), f.zone);
		long running = 0;
		int i = 0;
		for (; !h.isAfter(end); h = h.plusHours(1)) {
			long hourEnd = h.plusHours(1).atZone(f.zone).toInstant().toEpochMilli();
			// The hour starts with whatever was already up, then sees every change in it.
			long peak = running;
			for (; i < deltas.size() && deltas.get(i)[0] < hourEnd; i++) {
				running += deltas.get(i)[1];
				peak = Math.max(peak, running);
			}
			t.row(h.format(HOUR), peak);
		}
		return t;
	}

	/// Calls up right now: Vorpal-IDs some application still holds. A call row
	/// ends only when the last application on it lets go, so a call whose first
	/// application dropped out early (a proxy) still counts while a later one
	/// holds it. With it, the most up at once in the last hour and when.
	///
	/// As current as the analytics sink: a call counts once its session's
	/// start has crossed the bus and been written. The servlet caches this one
	/// for seconds, not minutes ([AnalyticsServlet#LIVE]).
	private Table callsNow(EntityManager em, Filter f) {
		Date since = new Date(f.now.getTime() - 3_600_000L);
		long running = 0, peak = 0, peakAt = since.getTime();
		for (long[] d : upDeltas(em, f, since)) {
			running += d[1];
			if (running > peak) {
				peak = running;
				peakAt = d[0];
			}
		}
		return new Table("now", "peak last hour", "peak at").row(running, peak,
				peak == 0 ? null : stamp(new Date(peakAt), f));
	}

	/// Calls per hour (a week or less) or per day, by how each ended, good to
	/// bad, in [#ENDINGS]' order.
	///
	/// Bucketed by when the call started. A call still up, or one no
	/// application reported an end for, is in no part.
	private Table callsByOutcome(EntityManager em, Filter f) {
		boolean hourly = f.days <= 7;
		Map<String, Object[]> ends = endings(em, f);
		Map<String, long[]> byBucket = new HashMap<>();
		for (Object[] r : list(em, "SELECT c.callId, c.startedAt FROM CallRow c" + where("c", "c.startedAt", f), f,
				true)) {
			Object[] end = ends.get((String) r[0]);
			if (end != null) {
				Instant at = instant(r[1]).toInstant();
				String key = hourly ? hour(at, f).format(HOUR) : day(at, f);
				byBucket.computeIfAbsent(key, k -> new long[ENDINGS.length])[(Integer) end[0]]++;
			}
		}
		List<String> buckets = new ArrayList<>();
		if (hourly) {
			LocalDateTime h = LocalDateTime.ofInstant(f.since().toInstant(), f.zone).withMinute(0).withSecond(0).withNano(0);
			LocalDateTime last = LocalDateTime.ofInstant(f.now.toInstant(), f.zone);
			for (; !h.isAfter(last); h = h.plusHours(1)) {
				buckets.add(h.format(HOUR));
			}
		} else {
			buckets.addAll(fillDays(new TreeMap<String, Long>(), f).keySet());
		}
		Table t = new Table(endingColumns(hourly ? "hour" : "day"));
		for (String b : buckets) {
			t.row(endingRow(b, byBucket.getOrDefault(b, new long[ENDINGS.length])));
		}
		return t;
	}

	/// Calls by where the caller is, and how each ended, good to bad in
	/// [#ENDINGS]' order. Most calls first.
	///
	/// `area` is what the board's map places: a North American area code, or
	/// `+` and the first three digits of any other number. The framework
	/// records it as the call's `area` key where the call enters BLADE
	/// ([CallOrigin#AREA_KEY]); a call from before that, or one an application
	/// filed under a `caller` key of its own configuration, is placed from that
	/// key. The browser turns an area into a place, and this report never
	/// returns a caller's number, so a grant without content sees it. A call
	/// with neither key, or a caller no map can place, is in the row with no
	/// area. As [#callsByOutcome], calls that started in the window and ended.
	private Table callsByArea(EntityManager em, Filter f) {
		Map<String, Object[]> ends = endings(em, f);
		Map<String, String> areas = new HashMap<>();
		for (Object[] r : list(em, "SELECT k.callId, k.keyName, k.keyValue FROM KeyRow k, CallRow c"
				+ where("c", "c.startedAt", f) + " AND k.callId = c.callId AND k.keyName IN ('area', 'caller')", f,
				true)) {
			String area = "area".equals(r[1]) ? (String) r[2] : area((String) r[2]);
			if (area != null && ("area".equals(r[1]) || !areas.containsKey((String) r[0]))) {
				areas.put((String) r[0], area);
			}
		}
		Map<String, long[]> byArea = new HashMap<>();
		TypedQuery<String> calls = em.createQuery("SELECT c.callId FROM CallRow c" + where("c", "c.startedAt", f),
				String.class);
		bind(calls, f, true);
		for (String call : calls.getResultList()) {
			Object[] end = ends.get(call);
			if (end != null) {
				byArea.computeIfAbsent(areas.getOrDefault(call, ""), k -> new long[ENDINGS.length])[(Integer) end[0]]++;
			}
		}
		List<Map.Entry<String, long[]>> rows = new ArrayList<>(byArea.entrySet());
		rows.sort(Comparator.comparing((Map.Entry<String, long[]> e) -> -Arrays.stream(e.getValue()).sum())
				.thenComparing(Map.Entry::getKey));
		Table t = new Table(endingColumns("area"));
		for (Map.Entry<String, long[]> e : rows) {
			t.row(endingRow(e.getKey().isEmpty() ? null : e.getKey(), e.getValue()));
		}
		return t;
	}

	/// The number in a `sip:`, `sips:` or `tel:` address: an optional `+`,
	/// then the digits up to the host or a parameter.
	private static final Pattern CALLER = Pattern.compile("^(?:sips?|tel):(\\+?[0-9]+)(?:[@;]|$)",
			Pattern.CASE_INSENSITIVE);

	/// The area of a caller's address, as [CallOrigin#area] finds it in the
	/// address's number; null for an address with none (an extension, an
	/// anonymous caller).
	static String area(String caller) {
		Matcher m = caller == null ? null : CALLER.matcher(caller.trim());
		return m != null && m.find() ? CallOrigin.area(m.group(1)) : null;
	}

	/// Calls per address their first INVITE came from ([CallOrigin#ORIGIN_KEY]),
	/// started at or after `from`: `origin`, `calls`, `last call` (a [Date]).
	/// The network map matches each address to an inbound site and says when
	/// that site last sent a call.
	public Table origins(Filter f, Date from) {
		EntityManager em = factory.createEntityManager();
		try {
			TypedQuery<Object[]> q = em.createQuery("SELECT k.keyValue, COUNT(c.callId), MAX(c.startedAt)"
					+ " FROM KeyRow k, CallRow c" + where("c", null, f) + " AND k.callId = c.callId"
					+ " AND k.keyName = 'origin' AND c.startedAt >= :from GROUP BY k.keyValue", Object[].class);
			bind(q, f, false);
			q.setParameter("from", from);
			Table t = new Table("origin", "calls", "last call");
			for (Object[] r : q.getResultList()) {
				t.row(r[0], ((Number) r[1]).longValue(), instant(r[2]));
			}
			return t;
		} finally {
			em.close();
		}
	}

	/// Legs servers refused at or after `from` (`branchFailed`), by where they
	/// were sent, who answered and how: `destination`, `responder`, `status`,
	/// `not sent` (the INVITE never left: no connection), `calls`, `last` (a
	/// [Date]), `retry after` (the longest asked). Every final response of 400
	/// or above; [SipStatus] says what each means.
	public Table failures(Filter f, Date from) {
		EntityManager em = factory.createEntityManager();
		try {
			TypedQuery<Object[]> q = em.createQuery("SELECT r.destination, r.responder, r.status, r.reason,"
					+ " COUNT(r.eventId), MAX(r.occurredAt), MAX(r.retryAfter) FROM RoutingRow r" + where("r", null, f)
					+ " AND r.eventType = 'branchFailed' AND r.status >= 400 AND r.occurredAt >= :from"
					+ " GROUP BY r.destination, r.responder, r.status, r.reason", Object[].class);
			bind(q, f, false);
			q.setParameter("from", from);
			Map<List<Object>, Object[]> merged = new LinkedHashMap<>();
			for (Object[] r : q.getResultList()) {
				boolean notSent = r[3] != null && ((String) r[3]).startsWith(Legs.NOT_SENT);
				List<Object> key = Arrays.asList(r[0], r[1], r[2] == null ? null : ((Number) r[2]).longValue(), notSent);
				Object[] m = merged.computeIfAbsent(key, k -> new Object[] { 0L, null, null });
				m[0] = (Long) m[0] + ((Number) r[4]).longValue();
				Date last = instant(r[5]);
				if (m[1] == null || last != null && last.after((Date) m[1])) {
					m[1] = last;
				}
				if (r[6] != null && (m[2] == null || ((Number) r[6]).longValue() > (Long) m[2])) {
					m[2] = ((Number) r[6]).longValue();
				}
			}
			Table t = new Table("destination", "responder", "status", "not sent", "calls", "last", "retry after");
			merged.forEach((k, m) -> t.row(k.get(0), k.get(1), k.get(2), k.get(3), m[0], m[1], m[2]));
			return t;
		} finally {
			em.close();
		}
	}

	/// +1 at each call's start and −1 at each end, for every call up at some
	/// point from `since` to now, in time order. A call already up at `since`
	/// starts there; a session open longer than [#LEAK_MS] is a leak, not a
	/// call, and is left out, or it would hold every count up forever.
	private List<long[]> upDeltas(EntityManager em, Filter f, Date since) {
		TypedQuery<Object[]> q = em.createQuery("SELECT c.startedAt, c.endedAt FROM CallRow c" + where("c", null, f)
				+ " AND c.startedAt <= :now AND (c.endedAt IS NULL OR c.endedAt >= :since)", Object[].class);
		bind(q, f, false);
		q.setParameter("since", since);
		q.setParameter("now", f.now);
		List<long[]> deltas = new ArrayList<>();
		for (Object[] r : q.getResultList()) {
			long start = instant(r[0]).getTime();
			Date end = instant(r[1]);
			if (end == null && f.now.getTime() - start > f.leakMs) {
				continue;
			}
			deltas.add(new long[] { Math.max(start, since.getTime()), 1 });
			if (end != null) {
				deltas.add(new long[] { end.getTime(), -1 });
			}
		}
		// Ends before starts at the same instant: a call that ends as another
		// begins was not up alongside it.
		deltas.sort(Comparator.<long[]>comparingLong(d -> d[0]).thenComparingLong(d -> d[1]));
		return deltas;
	}

	/// The domains the data holds, for the domain selectors. Ignores the domain
	/// filter (it would only ever return itself).
	private Table callsDomains(EntityManager em, Filter f) {
		Filter all = f.allDomains();
		List<Object[]> rows = list(em, "SELECT c.clusterName, COUNT(c) FROM CallRow c" + where("c", "c.startedAt", all)
				+ " GROUP BY c.clusterName", all, true);
		rows.sort(byCountDesc(1));
		Table t = new Table("domain", "calls");
		for (Object[] r : rows) {
			t.row(label(r[0]), num(r[1]));
		}
		return t;
	}

	// ── duration ─────────────────────────────────────────────────────────────

	private Table durationSummary(EntityManager em, Filter f) {
		List<Double> all = new ArrayList<>();
		for (Object[] r : durations(em, f)) {
			all.add((Double) r[1]);
		}
		Collections.sort(all);
		Double avg = all.isEmpty() ? null : round(all.stream().mapToDouble(Double::doubleValue).average().getAsDouble());
		Double max = all.isEmpty() ? null : round(all.get(all.size() - 1));
		return new Table("average", "median", "longest").row(avg, median(all), max);
	}

	private Table durationBands(EntityManager em, Filter f) {
		String[] names = { "under 15 s", "15-30 s", "30-60 s", "1-3 min", "3-10 min", "10 min +" };
		double[] upper = { 15, 30, 60, 180, 600, Double.MAX_VALUE };
		long[] counts = new long[names.length];
		for (Object[] r : durations(em, f)) {
			double d = (Double) r[1];
			int i = 0;
			while (d >= upper[i]) {
				i++;
			}
			counts[i]++;
		}
		Table t = new Table("length", "calls");
		for (int i = 0; i < names.length; i++) {
			t.row(names[i], counts[i]);
		}
		return t;
	}

	private Table durationDaily(EntityManager em, Filter f) {
		// Sums and counts, not AVG: an average of quarter-hour averages is not
		// the day's average.
		Map<String, double[]> byDay = new TreeMap<>();
		for (Map.Entry<Instant, Object[]> e : quarters(em, "c.startedAt", "SUM(c.durationSeconds), COUNT(c)",
				"CallRow c", where("c", "c.startedAt", f) + " AND c.durationSeconds IS NOT NULL", f, true).entrySet()) {
			double[] a = byDay.computeIfAbsent(day(e.getKey(), f), k -> new double[2]);
			a[0] += ((Number) e.getValue()[0]).doubleValue();
			a[1] += num(e.getValue()[1]);
		}
		Table t = new Table("day", "average");
		for (String day : fillDays(new TreeMap<String, Long>(), f).keySet()) {
			double[] a = byDay.get(day);
			t.row(day, (a == null || a[1] == 0) ? null : round(a[0] / a[1]));
		}
		return t;
	}

	/// Calls, median, average and longest per application, most calls first.
	/// The median is why this is computed here: JPQL has none.
	private Table durationByApplication(EntityManager em, Filter f) {
		Map<String, Long> calls = new HashMap<>();
		for (Object[] r : list(em, "SELECT c.application, COUNT(c) FROM CallRow c" + where("c", "c.startedAt", f)
				+ " GROUP BY c.application", f, true)) {
			calls.put(label(r[0]), num(r[1]));
		}
		Map<String, List<Double>> byApp = new HashMap<>();
		for (Object[] r : durations(em, f)) {
			byApp.computeIfAbsent(label(r[0]), k -> new ArrayList<>()).add((Double) r[1]);
		}
		List<String> apps = new ArrayList<>(calls.keySet());
		apps.sort(Comparator.comparing((String a) -> -calls.get(a)).thenComparing(a -> a));
		Table t = new Table("application", "calls", "median", "average", "longest");
		for (String app : apps) {
			List<Double> d = byApp.getOrDefault(app, Collections.emptyList());
			Collections.sort(d);
			Double avg = d.isEmpty() ? null : round(d.stream().mapToDouble(Double::doubleValue).average().getAsDouble());
			t.row(app, calls.get(app), median(d), avg, d.isEmpty() ? null : round(d.get(d.size() - 1)));
		}
		return t;
	}

	/// (application, duration) for every ended call in the window, sorted, so a
	/// median is the middle element.
	private List<Object[]> durations(EntityManager em, Filter f) {
		return list(em, "SELECT c.application, c.durationSeconds FROM CallRow c" + where("c", "c.startedAt", f)
				+ " AND c.durationSeconds IS NOT NULL ORDER BY c.durationSeconds", f, true);
	}

	// ── outcomes ─────────────────────────────────────────────────────────────

	/// Over the applications that report answering: a conference bridge's
	/// calls would otherwise count as started and never answered.
	private Table outcomesSummary(EntityManager em, Filter f) {
		Table t = new Table("started", "answered", "completed", "lost");
		List<String> answering = answering(em, f);
		if (answering.isEmpty()) {
			return t.row(0L, 0L, 0L, 0L);
		}
		return t.row(distinctCalls(em, f, STARTED, answering), distinctCalls(em, f, ANSWERED, answering),
				distinctCalls(em, f, COMPLETED, answering), distinctCalls(em, f, LOST, answering));
	}

	/// The applications that published an answer in the window.
	private List<String> answering(EntityManager em, Filter f) {
		return new ArrayList<>(callsPerApplication(em, f, ANSWERED).keySet());
	}

	/// How calls ended, in [#ENDINGS]' order. Every application counts; each
	/// call once.
	private Table outcomesEnded(EntityManager em, Filter f) {
		long[] n = new long[ENDINGS.length];
		for (Object[] end : endings(em, f).values()) {
			n[(Integer) end[0]]++;
		}
		Table t = new Table("outcome", "calls");
		for (int i = 0; i < ENDINGS.length; i++) {
			t.row(ENDINGS[i], n[i]);
		}
		return t;
	}

	/// Calls per day by how each ended, each call once, on the day it ended.
	private Table outcomesDaily(EntityManager em, Filter f) {
		Map<String, long[]> byDay = new TreeMap<>();
		for (Object[] end : endings(em, f).values()) {
			byDay.computeIfAbsent(day(((Date) end[1]).toInstant(), f), k -> new long[ENDINGS.length])[(Integer) end[0]]++;
		}
		Table t = new Table(endingColumns("day"));
		for (String day : fillDays(new TreeMap<String, Long>(), f).keySet()) {
			t.row(endingRow(day, byDay.getOrDefault(day, new long[ENDINGS.length])));
		}
		return t;
	}

	/// The outcome names, good to bad: the order of every outcome report's
	/// columns.
	///
	/// - `completed`: answered, then hung up.
	/// - `abandoned`: the caller hung up before an answer.
	/// - `declined`: the callee refused: busy, unavailable, not found, no answer.
	/// - `failed`: the call could not be put through: nothing answered the
	///   INVITE (the container's own 408 or 503, an INVITE never sent), a server
	///   error (5xx), or a refusal on credentials (401, 403, 407).
	/// - `dropped`: signaling a bug stopped relaying: the call expired with its
	///   legs out of step, or the caller gave up on an answer that never reached
	///   them.
	static final String[] ENDINGS = { "completed", "abandoned", "declined", "failed", "dropped" };
	static final int COMPLETED_AT = 0, ABANDONED_AT = 1, DECLINED_AT = 2, FAILED_AT = 3, DROPPED_AT = 4;

	/// A first column, then one per outcome.
	private static String[] endingColumns(String first) {
		String[] c = new String[ENDINGS.length + 1];
		c[0] = first;
		System.arraycopy(ENDINGS, 0, c, 1, ENDINGS.length);
		return c;
	}

	private static Object[] endingRow(Object first, long[] n) {
		Object[] r = new Object[n.length + 1];
		r[0] = first;
		for (int i = 0; i < n.length; i++) {
			r[i + 1] = n[i];
		}
		return r;
	}

	/// Each call that ended in the window, by call id: the index of its
	/// outcome in [#ENDINGS] and when it ended.
	///
	/// **One outcome per call.** A call through two applications reports its
	/// end from each, and not always the same end: the caller hangs up on a
	/// call one leg had answered. Dropped wins, since any application that lost
	/// a call's signaling broke it for the caller; then completed, so a call
	/// answered somewhere reads as completed; then abandoned, then a refusal.
	///
	/// **A refusal is declined or failed by its status.** The framework records
	/// every leg a server refused (`branchFailed`, with the status and the
	/// address that answered). A call's refusal is judged by the last such leg:
	/// see [#failure]. A call from before those records reads as declined.
	///
	/// **An abandon can be a drop.** The caller cancels a call that is still
	/// ringing. One application cannot both see the callee answer or refuse and
	/// then see the caller give up waiting, unless that answer never reached the
	/// caller. So an abandon after the same application's `callAnswered`, or
	/// after a leg it sent was refused with anything but the 487 a cancel
	/// itself causes, is dropped. An application that tries a second
	/// destination after a refusal would read as dropped too; no BLADE B2BUA
	/// does.
	private Map<String, Object[]> endings(EntityManager em, Filter f) {
		Map<String, Object[]> byCall = new HashMap<>();
		for (Object[] end : applicationEndings(em, f)) {
			Object[] seen = byCall.get((String) end[0]);
			if (seen == null || rank((Integer) end[2]) > rank((Integer) seen[0])) {
				byCall.put((String) end[0], new Object[] { end[2], end[3] });
			}
		}
		return byCall;
	}

	/// Every end an application reported in the window, judged as
	/// [#endings] describes: {call, application, outcome index, when}.
	private List<Object[]> applicationEndings(EntityManager em, Filter f) {
		List<String> types = new ArrayList<>(COMPLETED);
		types.addAll(DECLINED);
		types.addAll(ABANDONED);
		types.addAll(DROPPED);
		types.addAll(ANSWERED);
		TypedQuery<Object[]> q = em.createQuery("SELECT e.callId, e.eventType, e.application, MIN(e.occurredAt)"
				+ " FROM EventRow e" + where("e", "e.occurredAt", f)
				+ " AND e.callId IS NOT NULL AND LOWER(e.eventType) IN :types"
				+ " GROUP BY e.callId, e.eventType, e.application", Object[].class);
		bind(q, f, true);
		q.setParameter("types", types);

		// Per call: [end type index or -1, when], plus what an abandon is judged by.
		Map<String, Map<String, Date>> answeredBy = new HashMap<>();
		Map<String, List<Object[]>> ends = new HashMap<>();
		for (Object[] r : q.getResultList()) {
			String type = String.valueOf(r[1]).toLowerCase(Locale.ROOT);
			String call = (String) r[0];
			Date at = instant(r[3]);
			if (ANSWERED.contains(type)) {
				answeredBy.computeIfAbsent(call, k -> new HashMap<>()).merge(label(r[2]), at,
						(a, b) -> a.before(b) ? a : b);
			} else {
				ends.computeIfAbsent(call, k -> new ArrayList<>()).add(new Object[] { type, label(r[2]), at });
			}
		}
		Map<String, List<Object[]>> refusals = refusals(em, f);

		List<Object[]> out = new ArrayList<>();
		for (Map.Entry<String, List<Object[]>> e : ends.entrySet()) {
			String call = e.getKey();
			for (Object[] end : e.getValue()) {
				String type = (String) end[0];
				String app = (String) end[1];
				Date at = (Date) end[2];
				int outcome;
				if (DROPPED.contains(type)) {
					outcome = DROPPED_AT;
				} else if (COMPLETED.contains(type)) {
					outcome = COMPLETED_AT;
				} else if (ABANDONED.contains(type)) {
					outcome = droppedBeforeAbandon(answeredBy.get(call), refusals.get(call), app, at) ? DROPPED_AT
							: ABANDONED_AT;
				} else {
					outcome = failure(refusals.get(call), at) ? FAILED_AT : DECLINED_AT;
				}
				out.add(new Object[] { call, app, outcome, at });
			}
		}
		return out;
	}

	/// Each call's refused legs in the window, oldest first: {application,
	/// when, status, responder}.
	private Map<String, List<Object[]>> refusals(EntityManager em, Filter f) {
		Map<String, List<Object[]>> byCall = new HashMap<>();
		for (Object[] r : list(em, "SELECT r.callId, r.application, r.occurredAt, r.status, r.responder FROM RoutingRow r"
				+ where("r", "r.occurredAt", f) + " AND r.callId IS NOT NULL AND r.eventType = '" + BRANCH_FAILED
				+ "' ORDER BY r.occurredAt", f, true)) {
			byCall.computeIfAbsent((String) r[0], k -> new ArrayList<>())
					.add(new Object[] { label(r[1]), instant(r[2]), r[3] == null ? 0 : ((Number) r[3]).intValue(), r[4] });
		}
		return byCall;
	}

	/// Whether a refused call failed rather than being declined, by the last
	/// leg refused at or before the refusal reached the caller.
	///
	/// Failed: nothing answered the INVITE (no `responder`: the container's own
	/// 408 or 503, or an INVITE never sent), a server error (5xx), or a refusal
	/// on credentials (401, 403, 407), which is configuration, not the callee
	/// saying no. Everything else of 400 and up is the callee declining:
	/// busy, unavailable, not found, a 408 the callee's side sent for no answer.
	static boolean failure(List<Object[]> refused, Date at) {
		Object[] last = null;
		if (refused != null) {
			for (Object[] r : refused) {
				if (at == null || !((Date) r[1]).after(at)) {
					last = r;
				}
			}
		}
		if (last == null) {
			return false;
		}
		int status = (Integer) last[2];
		return last[3] == null || (status >= 500 && status < 600) || status == 401 || status == 403
				|| status == 407;
	}

	/// Whether the application that saw the caller abandon had already seen
	/// the callee answer, or seen a leg it sent refused (other than the 487 the
	/// cancel itself causes): an answer the caller never got.
	private static boolean droppedBeforeAbandon(Map<String, Date> answered, List<Object[]> refused, String app,
			Date at) {
		Date answeredAt = answered == null ? null : answered.get(app);
		if (answeredAt != null && at != null && answeredAt.before(at)) {
			return true;
		}
		if (refused != null && at != null) {
			for (Object[] r : refused) {
				if (app.equals(r[0]) && ((Date) r[1]).before(at) && (Integer) r[2] != 487) {
					return true;
				}
			}
		}
		return false;
	}

	/// Precedence among a call's outcomes: dropped, completed, abandoned, then
	/// a refusal (failed before declined).
	private static int rank(int outcome) {
		switch (outcome) {
		case DROPPED_AT:
			return 5;
		case COMPLETED_AT:
			return 4;
		case ABANDONED_AT:
			return 3;
		case FAILED_AT:
			return 2;
		default:
			return 1;
		}
	}

	/// Calls per application by how each ended, in [#ENDINGS]' order, most
	/// first. A call through two applications counts for each that reported its
	/// end, each by its own end: one application's refusal of a call another
	/// completed still counts as that application's refusal.
	private Table outcomesByApplication(EntityManager em, Filter f) {
		Map<String, Map<String, Integer>> perApp = new HashMap<>();
		for (Object[] end : applicationEndings(em, f)) {
			perApp.computeIfAbsent((String) end[1], k -> new HashMap<>()).merge((String) end[0], (Integer) end[2],
					(a, b) -> rank(a) >= rank(b) ? a : b);
		}
		Map<String, long[]> counts = new HashMap<>();
		perApp.forEach((app, calls) -> {
			long[] n = new long[ENDINGS.length];
			calls.values().forEach(o -> n[o]++);
			counts.put(app, n);
		});
		List<String> apps = new ArrayList<>(counts.keySet());
		apps.sort(Comparator.comparing((String a) -> -Arrays.stream(counts.get(a)).sum()).thenComparing(a -> a));
		Table t = new Table(endingColumns("application"));
		for (String app : apps) {
			t.row(endingRow(app, counts.get(app)));
		}
		return t;
	}

	/// Distinct calls with any of `types`, by `applications`.
	private long distinctCalls(EntityManager em, Filter f, List<String> types, List<String> applications) {
		TypedQuery<Long> q = em.createQuery("SELECT COUNT(DISTINCT e.callId) FROM EventRow e"
				+ where("e", "e.occurredAt", f) + " AND LOWER(e.eventType) IN :types AND e.application IN :apps",
				Long.class);
		bind(q, f, true);
		q.setParameter("types", types);
		q.setParameter("apps", applications);
		Long n = q.getSingleResult();
		return n == null ? 0 : n;
	}

	private Map<String, Long> callsPerApplication(EntityManager em, Filter f, List<String> types) {
		TypedQuery<Object[]> q = em.createQuery("SELECT e.application, COUNT(DISTINCT e.callId) FROM EventRow e"
				+ where("e", "e.occurredAt", f) + " AND LOWER(e.eventType) IN :types GROUP BY e.application",
				Object[].class);
		bind(q, f, true);
		q.setParameter("types", types);
		Map<String, Long> m = new HashMap<>();
		for (Object[] r : q.getResultList()) {
			m.merge(label(r[0]), num(r[1]), Long::sum);
		}
		return m;
	}

	/// Calls per day with an event of these types, each counted once, on the
	/// day of its first such event.
	///
	/// **Per call, not per quarter-hour.** A distinct count cannot be summed
	/// from smaller buckets: two applications' events for one call either side
	/// of a quarter-hour would count it twice. So the database returns each
	/// call's first matching instant, and the day is cut here.
	private Map<String, Long> callsPerDay(EntityManager em, Filter f, List<String> types, List<String> applications) {
		Map<String, Long> byDay = new TreeMap<>();
		if (applications.isEmpty()) {
			return byDay;
		}
		TypedQuery<Object[]> q = em.createQuery("SELECT e.callId, MIN(e.occurredAt) FROM EventRow e"
				+ where("e", "e.occurredAt", f) + " AND e.callId IS NOT NULL AND LOWER(e.eventType) IN :types"
				+ " AND e.application IN :apps GROUP BY e.callId", Object[].class);
		bind(q, f, true);
		q.setParameter("types", types);
		q.setParameter("apps", applications);
		for (Object[] r : q.getResultList()) {
			byDay.merge(day(instant(r[1]).toInstant(), f), 1L, Long::sum);
		}
		return byDay;
	}

	// ── sessions ─────────────────────────────────────────────────────────────

	/// Sessions never closed, whenever they started: a leak is a leak however
	/// old, so these ignore the window (the sample and domain filters still apply).
	private Table sessionsSummary(EntityManager em, Filter f) {
		TypedQuery<Object[]> q = em.createQuery(
				"SELECT COUNT(c), MIN(c.startedAt) FROM CallRow c" + where("c", null, f) + " AND c.endedAt IS NULL",
				Object[].class);
		bind(q, f, false);
		Object[] r = q.getSingleResult();
		return new Table("open", "oldest").row(num(r[0]), stamp(r[1], f));
	}

	private Table sessionsOpen(EntityManager em, Filter f) {
		TypedQuery<Object[]> q = em.createQuery("SELECT c.vorpalId, c.application, c.server, c.startedAt FROM CallRow c"
				+ where("c", null, f) + " AND c.endedAt IS NULL ORDER BY c.startedAt DESC", Object[].class);
		bind(q, f, false);
		q.setMaxResults(500);
		Table t = new Table("vorpal id", "application", "engine", "started");
		for (Object[] r : q.getResultList()) {
			t.row(label(r[0]), label(r[1]), label(r[2]), stamp(r[3], f));
		}
		return t;
	}

	/// Open sessions per application and engine, against the calls each handled
	/// in the window, most open first.
	private Table sessionsByApplication(EntityManager em, Filter f) {
		Map<String, Long> open = new HashMap<>();
		for (Object[] r : list(em, "SELECT c.application, c.server, COUNT(c) FROM CallRow c" + where("c", null, f)
				+ " AND c.endedAt IS NULL GROUP BY c.application, c.server", f, false)) {
			open.put(label(r[0]) + '\u0000' + label(r[1]), num(r[2]));
		}
		Map<String, Long> calls = new HashMap<>();
		for (Object[] r : list(em, "SELECT c.application, c.server, COUNT(c) FROM CallRow c"
				+ where("c", "c.startedAt", f) + " GROUP BY c.application, c.server", f, true)) {
			calls.put(label(r[0]) + '\u0000' + label(r[1]), num(r[2]));
		}
		List<String> keys = new ArrayList<>(open.keySet());
		keys.sort(Comparator.comparing((String k) -> -open.get(k)).thenComparing(k -> k));
		Table t = new Table("application", "engine", "open", "calls in window");
		for (String k : keys) {
			String[] p = k.split("\u0000", 2);
			t.row(p[0], p[1], open.get(k), calls.getOrDefault(k, 0L));
		}
		return t;
	}

	// ── platform ─────────────────────────────────────────────────────────────

	private Table platformSummary(EntityManager em, Filter f) {
		Map<String, Long> n = new HashMap<>();
		for (Object[] r : list(em, "SELECT e.eventType, COUNT(e) FROM EventRow e" + where("e", "e.occurredAt", f)
				+ " AND e.eventType IN ('start', 'stop') GROUP BY e.eventType", f, true)) {
			n.put((String) r[0], num(r[1]));
		}
		return new Table("starts", "stops").row(n.getOrDefault("start", 0L), n.getOrDefault("stop", 0L));
	}

	private Table platformDaily(EntityManager em, Filter f) {
		Map<String, Long> starts = dayCounts(em, "e.occurredAt", "COUNT(e)", "EventRow e", "e", f, null,
				" AND e.eventType = 'start'");
		Table t = new Table("day", "starts");
		for (Map.Entry<String, Long> e : fillDays(starts, f).entrySet()) {
			t.row(e.getKey(), e.getValue());
		}
		return t;
	}

	private Table platformByApplication(EntityManager em, Filter f) {
		Map<String, long[]> byApp = new HashMap<>();
		for (Object[] r : list(em, "SELECT e.application, e.eventType, COUNT(e) FROM EventRow e"
				+ where("e", "e.occurredAt", f) + " AND e.eventType IN ('start', 'stop') GROUP BY e.application, "
				+ "e.eventType", f, true)) {
			long[] ss = byApp.computeIfAbsent(label(r[0]), k -> new long[2]);
			ss["start".equals(r[1]) ? 0 : 1] += num(r[2]);
		}
		List<String> apps = new ArrayList<>(byApp.keySet());
		apps.sort(Comparator.comparing((String a) -> -byApp.get(a)[0]).thenComparing(a -> a));
		Table t = new Table("application", "starts", "stops");
		for (String app : apps) {
			t.row(app, byApp.get(app)[0], byApp.get(app)[1]);
		}
		return t;
	}

	private Table platformVersions(EntityManager em, Filter f) {
		List<Object[]> rows = list(em, "SELECT c.application, c.applicationVersion, c.server, COUNT(c) FROM CallRow c"
				+ where("c", "c.startedAt", f) + " GROUP BY c.application, c.applicationVersion, c.server", f, true);
		rows.sort(byCountDesc(3));
		Table t = new Table("application", "version", "engine", "calls");
		for (Object[] r : rows) {
			t.row(label(r[0]), label(r[1]), label(r[2]), num(r[3]));
		}
		return t;
	}

	// ── events ───────────────────────────────────────────────────────────────

	/// The per-minute samples are events too, but they have pages of their own
	/// and, at a few a minute from every server, would bury the facts these
	/// reports are about.
	private static final String NOT_SAMPLES = " AND e.eventType NOT IN ('applicationSampled', 'serverSampled')";

	private Table eventsSummary(EntityManager em, Filter f) {
		TypedQuery<Object[]> q = em.createQuery("SELECT COUNT(e), COUNT(DISTINCT e.eventType) FROM EventRow e"
				+ where("e", "e.occurredAt", f) + NOT_SAMPLES, Object[].class);
		bind(q, f, true);
		Object[] r = q.getSingleResult();
		return new Table("events", "types").row(num(r[0]), num(r[1]));
	}

	private Table eventsDaily(EntityManager em, Filter f) {
		Table t = new Table("day", "events");
		for (Map.Entry<String, Long> e : fillDays(dayCounts(em, "e.occurredAt", "COUNT(e)", "EventRow e", "e", f, null,
				NOT_SAMPLES), f).entrySet()) {
			t.row(e.getKey(), e.getValue());
		}
		return t;
	}

	private Table eventsByType(EntityManager em, Filter f) {
		List<Object[]> rows = list(em, "SELECT e.eventType, COUNT(e) FROM EventRow e" + where("e", "e.occurredAt", f)
				+ NOT_SAMPLES + " GROUP BY e.eventType", f, true);
		rows.sort(byCountDesc(1));
		Table t = new Table("type", "events");
		for (Object[] r : rows) {
			t.row(label(r[0]), num(r[1]));
		}
		return t;
	}

	private Table eventsByTypeApplication(EntityManager em, Filter f) {
		List<Object[]> rows = list(em, "SELECT e.eventType, e.application, COUNT(e) FROM EventRow e"
				+ where("e", "e.occurredAt", f) + NOT_SAMPLES + " GROUP BY e.eventType, e.application", f, true);
		rows.sort(byCountDesc(2));
		Table t = new Table("type", "application", "events");
		for (Object[] r : rows) {
			t.row(label(r[0]), label(r[1]), num(r[2]));
		}
		return t;
	}

	// ── risk ─────────────────────────────────────────────────────────────────

	private Table riskSummary(EntityManager em, Filter f) {
		TypedQuery<Object[]> q = em.createQuery("SELECT COUNT(DISTINCT r.callId), COUNT(r), AVG(r.riskScore) "
				+ "FROM RiskRow r" + where("r", "r.assessedAt", f), Object[].class);
		bind(q, f, true);
		Object[] r = q.getSingleResult();
		long flagged = single(em, "SELECT COUNT(DISTINCT r.callId) FROM RiskRow r" + where("r", "r.assessedAt", f)
				+ " AND r.eventType = 'callRiskFlagged'", f, true);
		return new Table("calls assessed", "assessments", "calls flagged", "average score").row(num(r[0]), num(r[1]),
				flagged, r[2] == null ? null : round(((Number) r[2]).doubleValue()));
	}

	/// Calls assessed and calls flagged per day, each on the day of its first
	/// such assessment. Calls, not assessments: a call is assessed every few
	/// seconds while it lasts, so a count of assessments measures talk time.
	private Table riskDaily(EntityManager em, Filter f) {
		Map<String, Long> assessed = riskCallsPerDay(em, f, "");
		Map<String, Long> flagged = riskCallsPerDay(em, f, " AND r.eventType = 'callRiskFlagged'");
		Table t = new Table("day", "calls assessed", "calls flagged");
		for (String day : fillDays(new TreeMap<String, Long>(), f).keySet()) {
			t.row(day, assessed.getOrDefault(day, 0L), flagged.getOrDefault(day, 0L));
		}
		return t;
	}

	private Map<String, Long> riskCallsPerDay(EntityManager em, Filter f, String and) {
		Map<String, Long> byDay = new TreeMap<>();
		for (Object[] r : list(em, "SELECT r.callId, MIN(r.assessedAt) FROM RiskRow r" + where("r", "r.assessedAt", f)
				+ " AND r.callId IS NOT NULL" + and + " GROUP BY r.callId", f, true)) {
			byDay.merge(day(instant(r[1]).toInstant(), f), 1L, Long::sum);
		}
		return byDay;
	}

	private Table riskByBand(EntityManager em, Filter f) {
		Map<String, Long> n = new HashMap<>();
		for (Object[] r : list(em, "SELECT r.riskBand, COUNT(r) FROM RiskRow r" + where("r", "r.assessedAt", f)
				+ " GROUP BY r.riskBand", f, true)) {
			n.merge(label(r[0]), num(r[1]), Long::sum);
		}
		return bandOrdered(n, "band", "assessments");
	}

	private Table riskByTrigger(EntityManager em, Filter f) {
		List<Object[]> rows = list(em, "SELECT r.triggerSignal, COUNT(r) FROM RiskRow r"
				+ where("r", "r.assessedAt", f) + " GROUP BY r.triggerSignal", f, true);
		rows.sort(byCountDesc(1));
		Table t = new Table("signal", "assessments");
		for (Object[] r : rows) {
			t.row(label(r[0]), num(r[1]));
		}
		return t;
	}

	/// Per band: how many log-odds each signal added on average, then each
	/// signal's raw reading. Worst band first.
	private Table riskSignals(EntityManager em, Filter f) {
		Map<String, Object[]> byBand = new HashMap<>();
		for (Object[] r : list(em, "SELECT r.riskBand, AVG(r.contributionAcoustic), AVG(r.contributionSignaling), "
				+ "AVG(r.contributionProvenance), AVG(r.contributionBehavior), AVG(r.signalAcoustic), "
				+ "AVG(r.signalSignaling), AVG(r.signalProvenance), AVG(r.signalBehavior) FROM RiskRow r"
				+ where("r", "r.assessedAt", f) + " GROUP BY r.riskBand", f, true)) {
			byBand.put(label(r[0]), r);
		}
		Table t = new Table("band", "acoustic contribution", "signaling contribution", "provenance contribution",
				"behavior contribution", "acoustic signal", "signaling signal", "provenance signal", "behavior signal");
		for (String band : bandsIn(byBand.keySet())) {
			Object[] r = byBand.get(band);
			Object[] row = new Object[9];
			row[0] = band;
			for (int i = 1; i < 9; i++) {
				row[i] = r[i] == null ? null : round(((Number) r[i]).doubleValue());
			}
			t.row(row);
		}
		return t;
	}

	private Table highriskByBand(EntityManager em, Filter f) {
		Map<String, Long> n = new HashMap<>();
		for (Object[] r : list(em, "SELECT s.peakRiskBand, COUNT(s) FROM RiskCallRow s"
				+ where("s", "s.lastAssessedAt", f) + " GROUP BY s.peakRiskBand", f, true)) {
			n.merge(label(r[0]), num(r[1]), Long::sum);
		}
		return bandOrdered(n, "worst band", "calls");
	}

	/// Calls that reached WATCH or SUSPECT, worst band first, then highest score.
	private Table highriskCalls(EntityManager em, Filter f) {
		TypedQuery<Object[]> q = em.createQuery("SELECT s.peakRiskBand, s.callId, s.firstAssessedAt, "
				+ "s.peakRiskScore, s.assessments FROM RiskCallRow s" + where("s", "s.lastAssessedAt", f)
				+ " AND s.peakRiskBand IN ('SUSPECT', 'WATCH') ORDER BY s.peakBandRank DESC, s.peakRiskScore DESC",
				Object[].class);
		bind(q, f, true);
		q.setMaxResults(500);
		Table t = new Table("worst band", "call", "first assessed", "peak score", "assessments");
		for (Object[] r : q.getResultList()) {
			t.row(label(r[0]), label(r[1]), stamp(r[2], f), r[3] == null ? null : round(((Number) r[3]).doubleValue()),
					r[4] == null ? null : num(r[4]));
		}
		return t;
	}

	// ── conversation ─────────────────────────────────────────────────────────

	private Table conversationSummary(EntityManager em, Filter f) {
		TypedQuery<Object[]> q = em.createQuery("SELECT COUNT(v), COUNT(DISTINCT v.callId) FROM ConversationRow v"
				+ where("v", "v.occurredAt", f) + " AND v.eventType = 'callerSaid'", Object[].class);
		bind(q, f, true);
		Object[] r = q.getSingleResult();
		return new Table("utterances", "calls with speech").row(num(r[0]), num(r[1]));
	}

	private Table conversationDaily(EntityManager em, Filter f) {
		Table t = new Table("day", "utterances");
		for (Map.Entry<String, Long> e : fillDays(dayCounts(em, "v.occurredAt", "COUNT(v)", "ConversationRow v", "v",
				f, null, " AND v.eventType = 'callerSaid'"), f).entrySet()) {
			t.row(e.getKey(), e.getValue());
		}
		return t;
	}

	private Table conversationBy(EntityManager em, Filter f, String path, String column) {
		List<Object[]> rows = list(em, "SELECT " + path + ", COUNT(v) FROM ConversationRow v"
				+ where("v", "v.occurredAt", f) + " AND v.eventType = 'callerSaid' GROUP BY " + path, f, true);
		rows.sort(byCountDesc(1));
		Table t = new Table(column, "utterances");
		for (Object[] r : rows) {
			t.row(label(r[0]), num(r[1]));
		}
		return t;
	}

	private Table conversationIntents(EntityManager em, Filter f) {
		List<Object[]> rows = list(em, "SELECT v.intent, v.entity, v.application, COUNT(v) FROM ConversationRow v"
				+ where("v", "v.occurredAt", f) + " AND v.eventType = 'callerSaid' GROUP BY v.intent, v.entity, "
				+ "v.application", f, true);
		rows.sort(byCountDesc(3));
		Table t = new Table("intent", "entity", "application", "utterances");
		for (Object[] r : rows.subList(0, Math.min(rows.size(), 200))) {
			t.row(label(r[0]), label(r[1]), label(r[2]), num(r[3]));
		}
		return t;
	}

	// ── data health ──────────────────────────────────────────────────────────

	private Table healthSummary(EntityManager em, Filter f) {
		Table coverage = healthCoverage(em, f);
		long gaps = coverage.rows.stream().filter(r -> !String.valueOf(r.get(6)).startsWith("none")).count();
		long split = splitCalls(em, f).size();
		long multi = multiApplicationCalls(em, f);
		Long dropped = sampleSum(em, f, "SELECT SUM(s.failures) FROM SampleRow s WHERE " + sampled("s", f));
		Long refused = sampleSum(em, f, "SELECT SUM(s.rejected) FROM ServerSampleRow s WHERE " + sampled("s", f));
		Long bypassed = sampleSum(em, f,
				"SELECT SUM(s.undeployedBypasses) FROM ServerSampleRow s WHERE " + sampled("s", f));
		return new Table("applications", "with gaps", "split calls", "calls in 2+ applications", "dropped events",
				"refused by the container", "routed past undeployed").row((long) coverage.rows.size(), gaps, split,
						multi, dropped, refused, bypassed);
	}

	/// A sum over one of the sample views, or null when that view is not
	/// installed. Views are applied by hand, so a dashboard can be upgraded
	/// before they exist; the tile is then empty, not the whole summary.
	private Long sampleSum(EntityManager em, Filter f, String jpql) {
		try {
			return num(samples(em, jpql, f).get(0));
		} catch (javax.persistence.PersistenceException noView) {
			return null;
		}
	}

	/// What each application reports about the calls it handles, and what it
	/// never reports.
	///
	/// **The check every other report depends on.** A count is only as good as
	/// the events behind it, and an application that never publishes, say,
	/// `callAnswered` does not show as an error anywhere: its calls just read as
	/// unanswered. This lists each application that handled a call in the window
	/// ([#callsPerHandler]), with the lifecycle events it sent for how many of
	/// those calls, and names in words the ones it never sent.
	///
	/// A gap is a fact, not always a fault. An application that only listens to
	/// a call may have nothing to answer; the report says what is missing and
	/// leaves the judgement to whoever owns the application.
	///
	/// "Never sends answered" is claimed only where an answer is owed: the
	/// application completed calls, or sends no end at all. One whose calls in
	/// the window were all declined or abandoned had nothing to answer.
	///
	/// One case is settled here: an application that sent `callRouted` and no
	/// lifecycle at all is a proxy. It picks the next hop and drops out of the
	/// call, so it has nothing to start, answer or end. It reads "none (routes
	/// only)" and is not counted as a gap.
	private Table healthCoverage(EntityManager em, Filter f) {
		Map<String, Long> calls = callsPerHandler(em, f);
		Map<String, Long> started = callsPerApplication(em, f, STARTED);
		Map<String, Long> answered = callsPerApplication(em, f, ANSWERED);
		List<String> ending = new ArrayList<>(COMPLETED);
		ending.addAll(LOST);
		Map<String, Long> ended = callsPerApplication(em, f, ending);
		Map<String, Long> completed = callsPerApplication(em, f, COMPLETED);
		Map<String, Long> routed = callsPerApplication(em, f, ROUTED);
		Map<String, Object> last = new HashMap<>();
		for (Object[] r : list(em, "SELECT e.application, MAX(e.occurredAt) FROM EventRow e"
				+ where("e", "e.occurredAt", f) + " AND e.callId IS NOT NULL GROUP BY e.application", f, true)) {
			last.put(label(r[0]), r[1]);
		}
		List<String> apps = new ArrayList<>(calls.keySet());
		apps.sort(Comparator.comparing((String a) -> -calls.get(a)).thenComparing(a -> a));
		Table t = new Table("application", "calls", "started", "answered", "ended", "last event", "gaps");
		for (String app : apps) {
			long s = started.getOrDefault(app, 0L);
			long a = answered.getOrDefault(app, 0L);
			long e = ended.getOrDefault(app, 0L);
			List<String> gaps = new ArrayList<>();
			if (s == 0) {
				gaps.add("never sends started");
			}
			// A missing answer shows only where one is owed: calls it completed,
			// which were answered, or no end at all, so nothing can be known. An
			// application whose calls were all refused or abandoned in the window
			// had nothing to answer.
			if (a == 0 && (completed.getOrDefault(app, 0L) > 0 || e == 0)) {
				gaps.add("never sends answered");
			}
			if (e == 0) {
				gaps.add("never sends an end");
			}
			boolean routesOnly = s == 0 && a == 0 && e == 0 && routed.getOrDefault(app, 0L) > 0;
			t.row(app, calls.get(app), s, a, e, stamp(last.get(app), f),
					routesOnly ? "none (routes only)" : gaps.isEmpty() ? "none" : String.join("; ", gaps));
		}
		return t;
	}

	/// What each application sent to the bus and what the bus refused, beside
	/// the INVITEs it received and the calls analytics recorded for it, from the
	/// per-minute samples of its metrics ([SampleRow]).
	///
	/// **The check that does not depend on the events being there.** Every
	/// other health report reads what arrived. A refused publish never arrives,
	/// so only the application's own count of it, sampled before a redeploy can
	/// zero it, says it happened. INVITEs received against calls recorded is the
	/// same check from the other side; the two will not match exactly, since an
	/// INVITE also re-negotiates a call that is already up, but a recorded count
	/// far below the INVITEs is calls going unrecorded.
	private Table healthPublishing(EntityManager em, Filter f) {
		Map<String, Long> recorded = callsPerHandler(em, f);
		List<Object[]> rows = samples(em, "SELECT s.application, SUM(s.invites), SUM(s.published), SUM(s.failures), "
				+ "SUM(s.sipErrors), COUNT(s), MAX(s.sampledAt) FROM SampleRow s WHERE " + sampled("s", f) + " "
				+ "GROUP BY s.application", f);
		Map<String, Long> restarts = new HashMap<>();
		for (Object[] r : this.<Object[]>samples(em, "SELECT s.application, COUNT(s) FROM SampleRow s "
				+ "WHERE " + sampled("s", f) + " AND s.restarted = 'true' GROUP BY s.application", f)) {
			restarts.put(label(r[0]), num(r[1]));
		}
		// Sessions held at each application's latest sample, summed over its
		// nodes: a count that stays up through quiet minutes is a leak.
		Map<String, Map<String, Object[]>> latest = new HashMap<>();
		for (Object[] r : this.<Object[]>samples(em, "SELECT s.application, s.node, s.sampledAt, s.activeAppSessions "
				+ "FROM SampleRow s WHERE " + sampled("s", f) + " ORDER BY s.sampledAt", f)) {
			latest.computeIfAbsent(label(r[0]), k -> new HashMap<>()).put(label(r[1]), r);
		}
		rows.sort(Comparator.comparing((Object[] r) -> -num(r[3])).thenComparing(r -> -num(r[2]))
				.thenComparing(r -> label(r[0])));
		Table t = new Table("application", "invites received", "calls recorded", "events published", "publishes failed",
				"sip errors", "restarts", "sessions held", "last sample");
		for (Object[] r : rows) {
			String app = label(r[0]);
			Long held = null;
			for (Object[] l : latest.getOrDefault(app, Collections.emptyMap()).values()) {
				if (l[3] != null) {
					held = (held == null ? 0 : held) + num(l[3]);
				}
			}
			t.row(app, num(r[1]), recorded.getOrDefault(app, 0L), num(r[2]), num(r[3]), num(r[4]),
					restarts.getOrDefault(app, 0L), held, stamp(r[6], f));
		}
		return t;
	}

	/// Events published and publishes refused, per day, across every application.
	private Table healthPublishingDaily(EntityManager em, Filter f) {
		Map<String, Long> published = sampleDays(em, f, "SUM(s.published)");
		Map<String, Long> failed = sampleDays(em, f, "SUM(s.failures)");
		Table t = new Table("day", "published", "failed");
		for (String day : fillDays(published, f).keySet()) {
			t.row(day, published.getOrDefault(day, 0L), failed.getOrDefault(day, 0L));
		}
		return t;
	}

	/// An aggregate of the samples per calendar day ([#samples], not [#dayCounts]).
	private Map<String, Long> sampleDays(EntityManager em, Filter f, String aggregate) {
		Map<String, Long> byDay = new TreeMap<>();
		for (Map.Entry<Instant, Object[]> e : quarters(em, "s.sampledAt", aggregate, "SampleRow s",
				" WHERE " + sampled("s", f), f, false).entrySet()) {
			byDay.merge(day(e.getKey(), f), num(e.getValue()[0]), Long::sum);
		}
		return byDay;
	}

	/// Each server over the window: SIP messages received and refused,
	/// application sessions created, and the worst readings seen, from the
	/// per-minute server samples ([ServerSampleRow]).
	///
	/// **"refused" is the column nothing else can show:** a message the
	/// container rejected under overload never reached an application. Nor
	/// did one FSMAR routed past an undeployed application: the caller was
	/// handled by whatever came next, and no error was logged. The session
	/// readings are the server's totals; which application holds them is in
	/// `health.publishing`.
	private Table platformServers(EntityManager em, Filter f) {
		// Keyed by domain and server: every domain has its own engine0.
		List<Object[]> rows = samples(em, "SELECT s.domain, s.node, SUM(s.received), SUM(s.rejected), "
				+ "SUM(s.appSessionsCreated), MAX(s.activeAppSessions), MAX(s.heapUsedPct), MAX(s.threadsStuck), "
				+ "MAX(s.threadsHogging), MAX(s.sampledAt), SUM(s.routed), SUM(s.routingFallbacks), "
				+ "SUM(s.undeployedBypasses), SUM(s.routingCycles) FROM ServerSampleRow s WHERE " + sampled("s", f)
				+ " GROUP BY s.domain, s.node", f);
		Map<String, Object[]> latest = new HashMap<>();
		for (Object[] r : this.<Object[]>samples(em, "SELECT s.domain, s.node, s.sampledAt, s.activeAppSessions, "
				+ "s.activeSipSessions, s.state FROM ServerSampleRow s WHERE " + sampled("s", f)
				+ " ORDER BY s.sampledAt", f)) {
			latest.put(label(r[0]) + '/' + label(r[1]), r);
		}
		Map<String, Long> restarts = new HashMap<>();
		for (Object[] r : this.<Object[]>samples(em, "SELECT s.domain, s.node, COUNT(s) FROM ServerSampleRow s "
				+ "WHERE " + sampled("s", f) + " AND s.restarted = 'true' GROUP BY s.domain, s.node", f)) {
			restarts.put(label(r[0]) + '/' + label(r[1]), num(r[2]));
		}
		rows.sort(Comparator.comparing((Object[] r) -> label(r[0])).thenComparing(r -> label(r[1])));
		Table t = new Table("domain", "server", "state", "messages received", "refused", "app sessions created",
				"app sessions now", "sip sessions now", "most app sessions", "peak heap %", "most stuck threads",
				"most hogging threads", "routed", "no rule matched", "routed past undeployed", "routing loops",
				"restarts", "last sample");
		for (Object[] r : rows) {
			String key = label(r[0]) + '/' + label(r[1]);
			Object[] now = latest.get(key);
			t.row(label(r[0]), label(r[1]), now == null ? null : now[5], num(r[2]), num(r[3]), num(r[4]),
					now == null ? null : now[3], now == null ? null : now[4], r[5], r[6], r[7], r[8], r[10], r[11],
					r[12], r[13], restarts.getOrDefault(key, 0L), stamp(r[9], f));
		}
		return t;
	}

	/// How long a server's last sample stands for it now: three missed
	/// minutes, and the board says it has gone quiet rather than show it as
	/// it last was. The default; the settings' `sampleFreshMinutes` replaces it.
	static final long SAMPLE_FRESH_MS = 3 * 60_000L;

	/// Each server's latest sample, in every domain (or the chosen one): how
	/// the board shows a domain whose runtime MBeans it cannot read, a minute
	/// behind. A server not sampled within [#SAMPLE_FRESH_MS] is left out; the
	/// window and the sample filter do not apply.
	private Table platformLatest(EntityManager em, Filter f) {
		javax.persistence.Query q = em.createQuery("SELECT s.domain, s.node, s.weblogicCluster, s.state, "
				+ "s.heapUsedPct, s.activeAppSessions, s.activeSipSessions, s.threadsStuck, s.threadsHogging, "
				+ "s.sampledAt, s.cpuPct FROM ServerSampleRow s WHERE s.sampledAt >= :fresh"
				+ (f.domain == null ? "" : " AND s.domain = :domain")
				+ (f.access == null ? "" : f.access.samplePredicate("s")) + " ORDER BY s.sampledAt");
		q.setParameter("fresh", new Date(f.now.getTime() - f.freshMs));
		if (f.domain != null) {
			q.setParameter("domain", f.domain);
		}
		if (f.access != null) {
			f.access.bind(q);
		}
		Map<String, Object[]> latest = new TreeMap<>();
		for (Object r : q.getResultList()) {
			Object[] row = (Object[]) r;
			latest.put(label(row[0]) + '/' + label(row[1]), row);
		}
		Table t = new Table("domain", "server", "weblogic cluster", "state", "heap %", "app sessions",
				"sip sessions", "stuck threads", "hogging threads", "sampled", "cpu %");
		for (Object[] r : latest.values()) {
			t.row(label(r[0]), label(r[1]), r[2], r[3], r[4], r[5], r[6], r[7], r[8], instant(r[9]).getTime(), r[10]);
		}
		return t;
	}

	/// Per hour across every server: messages received and refused, the most
	/// application sessions alive on any one server, the highest heap reading.
	private Table platformServersHourly(EntityManager em, Filter f) {
		// Sums add across a local hour's quarters; the readings take the highest.
		Map<LocalDateTime, Object[]> byHour = new TreeMap<>();
		for (Map.Entry<Instant, Object[]> e : quarters(em, "s.sampledAt",
				"SUM(s.received), SUM(s.rejected), MAX(s.activeAppSessions), MAX(s.heapUsedPct)", "ServerSampleRow s",
				" WHERE " + sampled("s", f), f, false).entrySet()) {
			Object[] q = e.getValue();
			Object[] a = byHour.computeIfAbsent(hour(e.getKey(), f), k -> new Object[] { 0L, 0L, null, null });
			a[0] = num(a[0]) + num(q[0]);
			a[1] = num(a[1]) + num(q[1]);
			a[2] = higher(a[2], q[2]);
			a[3] = higher(a[3], q[3]);
		}
		Table t = new Table("hour", "received", "refused", "most app sessions", "peak heap %");
		for (Map.Entry<LocalDateTime, Object[]> e : byHour.entrySet()) {
			Object[] r = e.getValue();
			t.row(e.getKey().format(HOUR), r[0], r[1], r[2], r[3]);
		}
		return t;
	}

	/// Each server's highest reading of one per-minute sample field (heap in
	/// use, CPU used) per hour (a week or less) or per day, so it reads over
	/// the same range as the call charts beside it. One row per server and
	/// hour or day that has a sample; a server named with its domain when the
	/// scope holds several.
	///
	/// Grouped by the UTC quarter-hour in the database, as [#quarters] does
	/// and for its reason, then folded into the viewer's hours or days here.
	///
	/// @param field  a [ServerSampleRow] reading, e.g. `heapUsedPct`
	/// @param column the result's value column
	private Table platformPeak(EntityManager em, Filter f, String field, String column) {
		boolean hourly = f.days <= 7;
		String parts = "EXTRACT(YEAR FROM s.sampledAt), EXTRACT(MONTH FROM s.sampledAt), EXTRACT(DAY FROM s.sampledAt),"
				+ " EXTRACT(HOUR FROM s.sampledAt), FUNCTION('FLOOR', EXTRACT(MINUTE FROM s.sampledAt) / 15)";
		List<Object[]> rows;
		try {
			TypedQuery<Object[]> q = em.createQuery("SELECT s.domain, s.node, " + parts + ", MAX(s." + field + ")"
					+ " FROM ServerSampleRow s WHERE " + sampled("s", f) + " GROUP BY s.domain, s.node, " + parts,
					Object[].class);
			bindSampled(q, f);
			rows = q.getResultList();
		} catch (javax.persistence.PersistenceException noView) {
			rows = Collections.emptyList();
		}
		boolean domains = rows.stream().map(r -> label(r[0])).distinct().count() > 1;
		Map<String, Map<String, Long>> byServer = new TreeMap<>();
		for (Object[] r : rows) {
			if (r[7] == null) {
				continue;
			}
			Instant start = LocalDateTime.of((int) num(r[2]), (int) num(r[3]), (int) num(r[4]), (int) num(r[5]),
					(int) num(r[6]) * 15).toInstant(ZoneOffset.UTC);
			String bucket = hourly ? hour(start, f).format(HOUR) : day(start, f);
			String server = domains ? label(r[0]) + "/" + label(r[1]) : label(r[1]);
			byServer.computeIfAbsent(server, k -> new TreeMap<>()).merge(bucket, Math.round(((Number) r[7]).doubleValue()),
					Math::max);
		}
		Table t = new Table(hourly ? "hour" : "day", "server", column);
		byServer.forEach((server, buckets) -> buckets.forEach((bucket, pct) -> t.row(bucket, server, pct)));
		return t;
	}

	// ── agent verdicts ───────────────────────────────────────────────────────

	/// What agents decided about the calls they handled: each outcome and
	/// treatment, how many, and how many numbers were blocked.
	private Table riskVerdicts(EntityManager em, Filter f) {
		List<Object[]> rows = list(em, "SELECT d.outcome, d.treatment, COUNT(d), COUNT(DISTINCT d.agent) "
				+ "FROM DispositionRow d" + where("d", "d.dispositionedAt", f) + " GROUP BY d.outcome, d.treatment",
				f, true);
		Map<String, Long> blocked = new HashMap<>();
		for (Object[] r : list(em, "SELECT d.outcome, d.treatment, COUNT(d) FROM DispositionRow d"
				+ where("d", "d.dispositionedAt", f) + " AND d.blocked = 'true' GROUP BY d.outcome, d.treatment", f,
				true)) {
			blocked.put(label(r[0]) + '\u0000' + label(r[1]), num(r[2]));
		}
		rows.sort(byCountDesc(2));
		Table t = new Table("outcome", "treatment", "calls", "blocked", "agents");
		for (Object[] r : rows) {
			t.row(label(r[0]), label(r[1]), num(r[2]), blocked.getOrDefault(label(r[0]) + '\u0000' + label(r[1]), 0L),
					num(r[3]));
		}
		return t;
	}

	/// The worst band each dispositioned call's risk reached, against the
	/// agent's verdict: whether the score was right.
	///
	/// **The check the risk score has never had.** A band means something only
	/// if SUSPECT calls turn out to be scams more often than CLEAR ones do. The
	/// agent's verdict is the ground truth; a call the agent judged and the
	/// risk tap never scored is counted under `(none)`. One column per outcome
	/// seen, bands worst first.
	private Table riskCalibration(EntityManager em, Filter f) {
		Map<String, Map<String, Long>> grid = new HashMap<>();
		TreeSet<String> outcomes = new TreeSet<>();
		for (Object[] r : list(em, "SELECT r.peakRiskBand, d.outcome, COUNT(d) FROM DispositionRow d, RiskCallRow r"
				+ where("d", "d.dispositionedAt", f) + " AND r.callId = d.callId GROUP BY r.peakRiskBand, d.outcome", f,
				true)) {
			outcomes.add(label(r[1]));
			grid.computeIfAbsent(label(r[0]), k -> new HashMap<>()).merge(label(r[1]), num(r[2]), Long::sum);
		}
		for (Object[] r : list(em, "SELECT d.outcome, COUNT(d) FROM DispositionRow d" + where("d", "d.dispositionedAt", f)
				+ " AND (d.callId IS NULL OR NOT EXISTS (SELECT r FROM RiskCallRow r WHERE r.callId = d.callId))"
				+ " GROUP BY d.outcome", f, true)) {
			outcomes.add(label(r[0]));
			grid.computeIfAbsent(NONE, k -> new HashMap<>()).merge(label(r[0]), num(r[1]), Long::sum);
		}
		List<String> columns = new ArrayList<>();
		columns.add("worst band");
		columns.addAll(outcomes);
		Table t = new Table(columns.toArray(new String[0]));
		List<String> bands = bandsIn(grid.keySet());
		for (String band : bands) {
			List<Object> row = new ArrayList<>();
			row.add(band);
			for (String outcome : outcomes) {
				row.add(grid.get(band).getOrDefault(outcome, 0L));
			}
			t.rows.add(row);
		}
		return t;
	}

	// ── application metrics ─────────────────────────────────────────────────

	/// Empty tiles, not an error, while the views are not installed (see
	/// [#sampleSum]): this one is on the overview.
	private Table metricsSummary(EntityManager em, Filter f) {
		Table t = new Table("applications counting", "counters that moved", "timings", "recordings");
		try {
			Object[] c = this.<Object[]>samples(em, "SELECT COUNT(DISTINCT c.application), COUNT(DISTINCT c.counter) "
					+ "FROM CounterRow c WHERE " + sampled("c", f), f).get(0);
			Object[] m = this.<Object[]>samples(em, "SELECT COUNT(DISTINCT t.timing), SUM(t.recordings) "
					+ "FROM TimingRow t WHERE " + sampled("t", f), f).get(0);
			return t.row(num(c[0]), num(c[1]), num(m[0]), num(m[1]));
		} catch (javax.persistence.PersistenceException noView) {
			return t.row(null, null, null, null);
		}
	}

	/// Every counter an application declares that moved in the window, with
	/// its total and when it last moved: the history of what each application
	/// counts about itself. Ordered by application, then most counted first.
	private Table metricsCounters(EntityManager em, Filter f) {
		List<Object[]> rows = samples(em, "SELECT c.application, c.counter, SUM(c.delta), MAX(c.sampledAt) "
				+ "FROM CounterRow c WHERE " + sampled("c", f) + " GROUP BY c.application, c.counter", f);
		rows.sort(Comparator.comparing((Object[] r) -> label(r[0])).thenComparing(r -> -num(r[2]))
				.thenComparing(r -> label(r[1])));
		Table t = new Table("application", "counter", "total", "last moved");
		for (Object[] r : rows) {
			t.row(label(r[0]), label(r[1]), num(r[2]), stamp(r[3], f));
		}
		return t;
	}

	/// Every timing an application recorded in the window: how many, and their
	/// mean. A mean over the window is exact from the per-sample sums; a
	/// percentile is not, which is why none is shown.
	private Table metricsTimings(EntityManager em, Filter f) {
		List<Object[]> rows = samples(em, "SELECT t.application, t.timing, SUM(t.recordings), SUM(t.sumMillis), "
				+ "MAX(t.sampledAt) FROM TimingRow t WHERE " + sampled("t", f) + " GROUP BY t.application, t.timing", f);
		rows.sort(Comparator.comparing((Object[] r) -> label(r[0])).thenComparing(r -> label(r[1])));
		Table t = new Table("application", "timing", "recordings", "mean ms", "last recorded");
		for (Object[] r : rows) {
			long n = num(r[2]);
			t.row(label(r[0]), label(r[1]), n, n == 0 ? null : round((double) num(r[3]) / n), stamp(r[4], f));
		}
		return t;
	}

	/// Runs a query over the samples, bound to the window alone: they carry no
	/// analytics `cluster_name`, so the sample and domain scopes do not apply.
	@SuppressWarnings("unchecked")
	private <T> List<T> samples(EntityManager em, String jpql, Filter f) {
		javax.persistence.Query q = em.createQuery(jpql);
		bindSampled(q, f);
		return q.getResultList();
	}

	/// The window and domain terms for a per-minute sample (`alias` over
	/// [SampleRow], [ServerSampleRow], [CounterRow] or [TimingRow]). Samples
	/// carry the publishing server's domain, not a `cluster_name`, so
	/// [#where] does not apply to them. Bind with [#bindSampled].
	static String sampled(String alias, Filter f) {
		return alias + ".sampledAt >= :since" + (f.domain == null ? "" : " AND " + alias + ".domain = :domain")
				+ (f.access == null ? "" : f.access.samplePredicate(alias));
	}

	private static void bindSampled(javax.persistence.Query q, Filter f) {
		q.setParameter("since", f.since());
		if (f.domain != null) {
			q.setParameter("domain", f.domain);
		}
		if (f.access != null) {
			f.access.bind(q);
		}
	}

	static final String CHAIN_SEPARATOR = " \u00b7 ";

	/// Which applications each call passed through, as counts per combination.
	private Table healthChains(EntityManager em, Filter f) {
		Map<String, Long> byChain = chains(em, f);
		List<String> keys = new ArrayList<>(byChain.keySet());
		keys.sort(Comparator.comparing((String k) -> -byChain.get(k)).thenComparing(k -> k));
		Table t = new Table("applications", "calls");
		for (String k : keys) {
			t.row(k, byChain.get(k));
		}
		return t;
	}

	/// Calls per set of applications that handled them ([#participation]), the
	/// names sorted and joined, so `listener · meetings` is one key.
	private Map<String, Long> chains(EntityManager em, Filter f) {
		Map<String, Long> byChain = new HashMap<>();
		for (TreeSet<String> apps : participation(em, f).values()) {
			byChain.merge(String.join(CHAIN_SEPARATOR, apps), 1L, Long::sum);
		}
		return byChain;
	}

	/// One call recorded as two: the same Vorpal-ID on two call rows born less
	/// than [#SPLIT_MS] apart.
	///
	/// **How it happens.** Every application on a call reports the same birth
	/// instant, read from the X-Vorpal-ID `ts` parameter, and that instant is
	/// part of the call's key. An application that finds no `ts` falls back to
	/// its own clock (`Analytics.getCallStartedAt`), and its events then key a
	/// second row, born when the call reached it. A Vorpal-ID is reused over
	/// time, but never while the first call is up, so two births a minute apart
	/// are the fallback, not reuse.
	private Table healthSplitCalls(EntityManager em, Filter f) {
		List<Object[]> pairs = splitCalls(em, f);
		Table t = new Table("vorpal id", "first row", "second row", "seconds apart", "first application",
				"second application");
		for (Object[] p : pairs.subList(0, Math.min(pairs.size(), 200))) {
			Object[] a = (Object[]) p[0];
			Object[] b = (Object[]) p[1];
			long ms = instant(b[2]).getTime() - instant(a[2]).getTime();
			t.row(label(a[1]), stamp(a[2], f), stamp(b[2], f), round(ms / 1000.0), label(a[3]), label(b[3]));
		}
		return t;
	}

	/// Adjacent rows of one correlator closer than [#SPLIT_MS], newest first.
	private List<Object[]> splitCalls(EntityManager em, Filter f) {
		// Only rows whose correlator has another row in the window: the database
		// narrows the set before it leaves.
		List<Object[]> rows = list(em, "SELECT c.clusterName, c.vorpalId, c.startedAt, c.application FROM CallRow c"
				+ where("c", "c.startedAt", f) + " AND EXISTS (SELECT d FROM CallRow d WHERE d.clusterName = c.clusterName"
				+ " AND d.vorpalId = c.vorpalId AND d.callId <> c.callId AND d.startedAt >= :since)"
				+ " ORDER BY c.clusterName, c.vorpalId, c.startedAt", f, true);
		List<Object[]> pairs = new ArrayList<>();
		for (int i = 1; i < rows.size(); i++) {
			Object[] a = rows.get(i - 1);
			Object[] b = rows.get(i);
			if (Objects.equals(a[0], b[0]) && Objects.equals(a[1], b[1])
					&& instant(b[2]).getTime() - instant(a[2]).getTime() < f.splitMs) {
				pairs.add(new Object[] { a, b });
			}
		}
		pairs.sort(Comparator.comparing((Object[] p) -> -instant(((Object[]) p[0])[2]).getTime()));
		return pairs;
	}

	// ── query plumbing ───────────────────────────────────────────────────────

	/// The scope clause for one alias: the time window (when `timePath` is
	/// given), the sample exclusion, the domain. Always starts " WHERE", so
	/// callers append further conditions with " AND".
	static String where(String alias, String timePath, Filter f) {
		return where(alias, timePath, f, alias);
	}

	/// [#where] for a call joined to its events, with the viewer's access
	/// applied to `accessAlias`, the event row, rather than to the call.
	///
	/// A whole call has no department, so its access rule is a subquery over
	/// `v_events`. Beside a join to `v_events` in the same query, Oracle ADB
	/// estimated that subquery at one row and planned a Cartesian product of
	/// it with every event and every application (rig, 2026-10-11: past an
	/// hour, for a viewer with a department rule). The event row carries its
	/// own department, so the rule needs no subquery there, and it counts
	/// what a department viewer may see: their own part of each call.
	static String where(String alias, String timePath, Filter f, String accessAlias) {
		StringBuilder w = new StringBuilder(" WHERE 1 = 1");
		if (timePath != null) {
			w.append(" AND ").append(timePath).append(" >= :since");
		}
		if (!f.includeSample) {
			w.append(" AND (").append(alias).append(".clusterName IS NULL OR ").append(alias)
					.append(".clusterName <> 'sample')");
		}
		if (f.domain != null) {
			w.append(" AND ").append(alias).append(".clusterName = :domain");
		}
		if (f.access != null) {
			w.append(f.access.predicate(accessAlias, !WHOLE_CALLS.contains(accessAlias)));
		}
		if (f.account != null) {
			w.append(" AND ").append(alias).append(".callId IN (SELECT k.callId FROM KeyRow k")
					.append(" WHERE k.keyName = 'account' AND k.keyValue = :account)");
		}
		return w.toString();
	}

	/// The aliases [#where] is given for a whole call ([CallRow] as `c`,
	/// [RiskCallRow] as `s`): rows with no department of their own. Every
	/// other alias is an event-based row that carries one.
	private static final Set<String> WHOLE_CALLS = new HashSet<>(Arrays.asList("c", "s"));

	private static void bind(TypedQuery<?> q, Filter f, boolean windowed) {
		if (windowed) {
			q.setParameter("since", f.since());
		}
		if (f.domain != null) {
			q.setParameter("domain", f.domain);
		}
		if (f.access != null) {
			f.access.bind(q);
		}
		if (f.account != null) {
			q.setParameter("account", f.account);
		}
	}

	private static List<Object[]> list(EntityManager em, String jpql, Filter f, boolean windowed) {
		TypedQuery<Object[]> q = em.createQuery(jpql, Object[].class);
		bind(q, f, windowed);
		return q.getResultList();
	}

	private static long single(EntityManager em, String jpql, Filter f, boolean windowed) {
		TypedQuery<Long> q = em.createQuery(jpql, Long.class);
		bind(q, f, windowed);
		Long n = q.getSingleResult();
		return n == null ? 0 : n;
	}

	private static long singleSince(EntityManager em, String jpql, Filter f, Date since) {
		TypedQuery<Long> q = em.createQuery(jpql, Long.class);
		bind(q, f, false);
		q.setParameter("since", since);
		Long n = q.getSingleResult();
		return n == null ? 0 : n;
	}

	private Map<String, Long> dayCounts(EntityManager em, String timePath, String aggregate, String from,
			String alias, Filter f, List<String> types) {
		return dayCounts(em, timePath, aggregate, from, alias, f, types, "");
	}

	/// A summable aggregate per calendar day in the viewer's zone, folded from
	/// [#quarters]. A count or a sum only: a distinct count or an average does
	/// not add up across quarters.
	private Map<String, Long> dayCounts(EntityManager em, String timePath, String aggregate, String from,
			String alias, Filter f, List<String> types, String extra) {
		Map<String, Long> byDay = new TreeMap<>();
		for (Map.Entry<Instant, Object[]> e : quarters(em, timePath, aggregate, from,
				where(alias, timePath, f) + extra
						+ (types == null ? "" : " AND LOWER(" + alias + ".eventType) IN :types"),
				f, true, types).entrySet()) {
			byDay.merge(day(e.getKey(), f), num(e.getValue()[0]), Long::sum);
		}
		return byDay;
	}

	/// A count or sum per hour in the viewer's zone, folded from [#quarters].
	private Map<LocalDateTime, Long> hourCounts(EntityManager em, String timePath, String aggregate, String from,
			String alias, Filter f) {
		Map<LocalDateTime, Long> byHour = new TreeMap<>();
		for (Map.Entry<Instant, Object[]> e : quarters(em, timePath, aggregate, from, where(alias, timePath, f), f,
				true).entrySet()) {
			byHour.merge(hour(e.getKey(), f), num(e.getValue()[0]), Long::sum);
		}
		return byHour;
	}

	private Map<Instant, Object[]> quarters(EntityManager em, String timePath, String aggregates, String from,
			String where, Filter f, boolean scoped) {
		return quarters(em, timePath, aggregates, from, where, f, scoped, null);
	}

	/// Aggregates per UTC quarter-hour, keyed by the instant each quarter starts.
	///
	/// **Why quarters.** The database holds UTC and is never told the viewer's
	/// zone: JPQL has no portable way to shift a timestamp, and each database's
	/// own way differs. Every zone in use sits a whole number of quarter-hours
	/// from UTC (India +5:30, Nepal +5:45), so no quarter straddles a local hour
	/// or day, and [#day] and [#hour] can cut either here, exactly. A month is
	/// under 3,000 rows.
	///
	/// @param where  the `WHERE` clause, with `:since` and, when `scoped`, the
	///               sample and domain terms ([#where])
	/// @param scoped whether to bind the domain as well as the window
	/// @param types  bound to `:types` when not null
	private Map<Instant, Object[]> quarters(EntityManager em, String timePath, String aggregates, String from,
			String where, Filter f, boolean scoped, List<String> types) {
		String parts = "EXTRACT(YEAR FROM " + timePath + "), EXTRACT(MONTH FROM " + timePath + "), EXTRACT(DAY FROM "
				+ timePath + "), EXTRACT(HOUR FROM " + timePath + "), FUNCTION('FLOOR', EXTRACT(MINUTE FROM "
				+ timePath + ") / 15)";
		TypedQuery<Object[]> q = em.createQuery("SELECT " + parts + ", " + aggregates + " FROM " + from + where
				+ " GROUP BY " + parts, Object[].class);
		if (scoped) {
			bind(q, f, true);
		} else {
			bindSampled(q, f);
		}
		if (types != null) {
			q.setParameter("types", types);
		}
		Map<Instant, Object[]> byQuarter = new HashMap<>();
		for (Object[] r : q.getResultList()) {
			Instant start = LocalDateTime.of((int) num(r[0]), (int) num(r[1]), (int) num(r[2]), (int) num(r[3]),
					(int) num(r[4]) * 15).toInstant(ZoneOffset.UTC);
			byQuarter.put(start, Arrays.copyOfRange(r, 5, r.length));
		}
		return byQuarter;
	}

	/// The viewer's calendar day an instant falls on, as `yyyy-MM-dd`.
	private static String day(Instant at, Filter f) {
		return LocalDate.ofInstant(at, f.zone).format(DAY);
	}

	/// The viewer's hour an instant falls in.
	private static LocalDateTime hour(Instant at, Filter f) {
		return LocalDateTime.ofInstant(at, f.zone).withMinute(0).withSecond(0).withNano(0);
	}

	/// A timestamp from a query result as the instant it stands for. A column
	/// selected through its entity attribute arrives converted ([UtcTimestamp]);
	/// one under an aggregate such as `MAX` may arrive as the raw column.
	private static Date instant(Object o) {
		return (o instanceof Timestamp) ? UtcTimestamp.fromDb((Timestamp) o) : (Date) o;
	}

	private static Object higher(Object a, Object b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return ((Number) a).doubleValue() >= ((Number) b).doubleValue() ? a : b;
	}

	/// Every day of the window in order, zero where nothing happened, so a gap
	/// shows as a gap instead of the bars closing ranks.
	private Map<String, Long> fillDays(Map<String, Long> byDay, Filter f) {
		Map<String, Long> all = new LinkedHashMap<>();
		LocalDate end = LocalDate.ofInstant(f.now.toInstant(), f.zone);
		for (LocalDate d = LocalDate.ofInstant(f.since().toInstant(), f.zone); !d.isAfter(end); d = d.plusDays(1)) {
			String key = d.format(DAY);
			all.put(key, byDay.getOrDefault(key, 0L));
		}
		return all;
	}

	/// Midnight today in the viewer's zone.
	private static Date startOfDay(Filter f) {
		return Date.from(LocalDate.ofInstant(f.now.toInstant(), f.zone).atStartOfDay(f.zone).toInstant());
	}

	private Table bandOrdered(Map<String, Long> counts, String bandColumn, String countColumn) {
		Table t = new Table(bandColumn, countColumn);
		for (String band : bandsIn(counts.keySet())) {
			t.row(band, counts.get(band));
		}
		return t;
	}

	/// The bands present, worst first; anything unexpected after them.
	private static List<String> bandsIn(java.util.Set<String> present) {
		List<String> out = new ArrayList<>();
		for (String b : BANDS) {
			if (present.contains(b)) {
				out.add(b);
			}
		}
		List<String> rest = new ArrayList<>(present);
		rest.removeAll(BANDS);
		Collections.sort(rest);
		out.addAll(rest);
		return out;
	}

	private static Comparator<Object[]> byCountDesc(int index) {
		return Comparator.comparing((Object[] r) -> -num(r[index])).thenComparing(r -> label(r[0]));
	}

	private static String weekday(DayOfWeek d) {
		String n = d.name();
		return n.charAt(0) + n.substring(1, 3).toLowerCase();
	}

	static Double median(List<Double> sorted) {
		int n = sorted.size();
		if (n == 0) {
			return null;
		}
		double m = (n % 2 == 1) ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
		return round(m);
	}

	private static long num(Object o) {
		return o == null ? 0 : ((Number) o).longValue();
	}

	private static String label(Object o) {
		return (o == null || o.toString().trim().isEmpty()) ? NONE : o.toString();
	}

	/// A time from a query result, printed in the viewer's zone.
	private static String stamp(Object o, Filter f) {
		return o == null ? null : LocalDateTime.ofInstant(instant(o).toInstant(), f.zone).format(STAMP);
	}

	private static Double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
