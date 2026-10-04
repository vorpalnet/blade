package org.vorpal.blade.applications.dashboard;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.TemporalType;
import javax.persistence.TypedQuery;

/// Every chart and table the dashboard draws, as a JPQL query over the
/// reporting views ([org.vorpal.blade.applications.dashboard.model]).
///
/// **Portable JPQL, finished in Java where JPQL stops.** The same queries run
/// on Oracle, MySQL and SQL Server because EclipseLink writes the SQL. JPA 2.2
/// has no portable date bucketing and no median, so:
///
/// - Day and hour buckets group on EclipseLink's `EXTRACT(part FROM date)`,
///   which it renders in each database's own dialect. The weekday is computed
///   here from the day, never by the database, because every database numbers
///   weekdays differently.
/// - Medians are taken here from the sorted durations of the window.
/// - Peak concurrency is swept here from start and end times.
///
/// **Sample rows are excluded unless asked for.** The analytics console's
/// sample-data generator writes synthetic calls under `cluster_name = 'sample'`.
/// Left in, they outnumber a lab's real traffic many times over, so every query
/// drops them by default ([Filter#includeSample]).
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
		final String cluster;
		final Date now;

		/// @param days          how far back the window reaches
		/// @param includeSample whether `cluster_name = 'sample'` rows count
		/// @param cluster       only this `cluster_name`, or null for all
		/// @param now           the end of the window (a parameter so tests can pin it)
		public Filter(int days, boolean includeSample, String cluster, Date now) {
			this.days = days;
			this.includeSample = includeSample;
			this.cluster = (cluster == null || cluster.isEmpty()) ? null : cluster;
			this.now = now;
		}

		Date since() {
			return new Date(now.getTime() - days * 86_400_000L);
		}

		/// The cache key for this scope plus a report name.
		String key(String report) {
			return report + '|' + days + '|' + includeSample + '|' + (cluster == null ? "" : cluster);
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

	/// The report names the servlet accepts, in the order a reader meets them.
	public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
			"calls.summary", "calls.daily", "calls.hourly", "calls.hour-of-day", "calls.weekday", "calls.heatmap",
			"calls.by-application", "calls.by-engine", "calls.by-tenant", "calls.concurrent", "calls.clusters",
			"duration.summary", "duration.bands", "duration.daily", "duration.by-application",
			"outcomes.summary", "outcomes.daily", "outcomes.funnel", "outcomes.by-application",
			"sessions.summary", "sessions.open", "sessions.by-application",
			"platform.summary", "platform.daily", "platform.by-application", "platform.versions",
			"events.summary", "events.daily", "events.by-type", "events.by-type-application",
			"risk.summary", "risk.daily", "risk.by-band", "risk.by-trigger", "risk.signals",
			"highrisk.by-band", "highrisk.calls",
			"conversation.summary", "conversation.daily", "conversation.by-intent", "conversation.by-addressed",
			"conversation.intents"));

	// Lifecycle names as the views carry them: the short names, plus the camelCase
	// and dotted forms older writers used. Compared lower-cased.
	static final List<String> STARTED = Arrays.asList("callstarted", "call.started", "started", "sessionstarted",
			"session.started", "sessionstart");
	/// Connected counts as answered, in every report, so the funnel and the
	/// daily answer counts agree.
	static final List<String> ANSWERED = Arrays.asList("callanswered", "call.answered", "answered", "callconnected",
			"call.connected", "connected");
	static final List<String> COMPLETED = Arrays.asList("callcompleted", "call.completed", "completed");
	static final List<String> ABANDONED = Arrays.asList("callabandoned", "call.abandoned", "abandoned");
	static final List<String> LOST = Arrays.asList("calldeclined", "call.declined", "declined", "callabandoned",
			"call.abandoned", "abandoned");

	/// Band order: worst first, top to bottom, so position carries the ranking as
	/// well as the word and the colour do.
	static final List<String> BANDS = Arrays.asList("SUSPECT", "WATCH", "CLEAR");

	private static final String NONE = "(none)";
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/// A session still open after this long is a leak, not a call: it is listed
	/// by `sessions.open` but kept out of peak concurrency.
	static final long LEAK_MS = 24 * 3_600_000L;

	private final EntityManagerFactory factory;
	private final ZoneId zone;

	public AnalyticsReports(EntityManagerFactory factory) {
		this(factory, ZoneId.systemDefault());
	}

	AnalyticsReports(EntityManagerFactory factory, ZoneId zone) {
		this.factory = factory;
		this.zone = zone;
	}

	/// Runs one named report.
	///
	/// @throws IllegalArgumentException for a name not in [#NAMES]
	public Table run(String name, Filter f) {
		EntityManager em = factory.createEntityManager();
		try {
			switch (name) {
			case "calls.summary": return callsSummary(em, f);
			case "calls.daily": return callsDaily(em, f);
			case "calls.hourly": return callsHourly(em, f);
			case "calls.hour-of-day": return callsHourOfDay(em, f);
			case "calls.weekday": return callsWeekday(em, f);
			case "calls.heatmap": return callsHeatmap(em, f);
			case "calls.by-application": return countBy(em, f, "c.application", "application");
			case "calls.by-engine": return countBy(em, f, "c.server", "engine");
			case "calls.by-tenant": return countBy(em, f, "c.tenant", "tenant");
			case "calls.concurrent": return callsConcurrent(em, f);
			case "calls.clusters": return callsClusters(em, f);
			case "duration.summary": return durationSummary(em, f);
			case "duration.bands": return durationBands(em, f);
			case "duration.daily": return durationDaily(em, f);
			case "duration.by-application": return durationByApplication(em, f);
			case "outcomes.summary": return outcomesSummary(em, f);
			case "outcomes.daily": return outcomesDaily(em, f);
			case "outcomes.funnel": return outcomesFunnel(em, f);
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
			default: throw new IllegalArgumentException("unknown report '" + name + "'");
			}
		} finally {
			em.close();
		}
	}

	// ── calls ────────────────────────────────────────────────────────────────

	private Table callsSummary(EntityManager em, Filter f) {
		long calls = single(em, "SELECT COUNT(c) FROM CallRow c" + where("c", "c.startedAt", f), f, true);
		Filter today = new Filter(f.days, f.includeSample, f.cluster, f.now);
		long callsToday = singleSince(em, "SELECT COUNT(c) FROM CallRow c" + where("c", "c.startedAt", today), today,
				startOfDay(f.now));
		long open = single(em, "SELECT COUNT(c) FROM CallRow c" + where("c", null, f) + " AND c.endedAt IS NULL", f,
				false);
		long apps = single(em, "SELECT COUNT(DISTINCT c.application) FROM CallRow c" + where("c", "c.startedAt", f), f,
				true);
		return new Table("calls", "today", "open", "applications").row(calls, callsToday, open, apps);
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
		List<Object[]> rows = list(em, "SELECT EXTRACT(YEAR FROM c.startedAt), EXTRACT(MONTH FROM c.startedAt), "
				+ "EXTRACT(DAY FROM c.startedAt), EXTRACT(HOUR FROM c.startedAt), COUNT(c) FROM CallRow c"
				+ where("c", "c.startedAt", f) + " GROUP BY EXTRACT(YEAR FROM c.startedAt), "
				+ "EXTRACT(MONTH FROM c.startedAt), EXTRACT(DAY FROM c.startedAt), EXTRACT(HOUR FROM c.startedAt)", f,
				true);
		TreeMap<String, Long> byHour = new TreeMap<>();
		for (Object[] r : rows) {
			String key = String.format("%04d-%02d-%02d %02d:00", num(r[0]), num(r[1]), num(r[2]), num(r[3]));
			byHour.merge(key, num(r[4]), Long::sum);
		}
		Table t = new Table("hour", "calls");
		LocalDateTime h = LocalDateTime.ofInstant(f.since().toInstant(), zone).withMinute(0).withSecond(0).withNano(0);
		LocalDateTime end = LocalDateTime.ofInstant(f.now.toInstant(), zone);
		DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
		for (; !h.isAfter(end); h = h.plusHours(1)) {
			String key = h.format(fmt);
			t.row(key, byHour.getOrDefault(key, 0L));
		}
		return t;
	}

	private Table callsHourOfDay(EntityManager em, Filter f) {
		long[] hours = new long[24];
		for (Object[] r : list(em, "SELECT EXTRACT(HOUR FROM c.startedAt), COUNT(c) FROM CallRow c"
				+ where("c", "c.startedAt", f) + " GROUP BY EXTRACT(HOUR FROM c.startedAt)", f, true)) {
			hours[(int) num(r[0])] += num(r[1]);
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
		for (Object[] r : list(em, "SELECT EXTRACT(YEAR FROM c.startedAt), EXTRACT(MONTH FROM c.startedAt), "
				+ "EXTRACT(DAY FROM c.startedAt), EXTRACT(HOUR FROM c.startedAt), COUNT(c) FROM CallRow c"
				+ where("c", "c.startedAt", f) + " GROUP BY EXTRACT(YEAR FROM c.startedAt), "
				+ "EXTRACT(MONTH FROM c.startedAt), EXTRACT(DAY FROM c.startedAt), EXTRACT(HOUR FROM c.startedAt)", f,
				true)) {
			int dow = LocalDate.of((int) num(r[0]), (int) num(r[1]), (int) num(r[2])).getDayOfWeek().getValue() - 1;
			grid[dow][(int) num(r[3])] += num(r[4]);
		}
		Table t = new Table("weekday", "hour", "calls");
		for (int d = 0; d < 7; d++) {
			for (int h = 0; h < 24; h++) {
				t.row(weekday(DayOfWeek.of(d + 1)), h, grid[d][h]);
			}
		}
		return t;
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
		TypedQuery<Object[]> q = em.createQuery("SELECT c.startedAt, c.endedAt FROM CallRow c" + where("c", null, f)
				+ " AND c.startedAt <= :now AND (c.endedAt IS NULL OR c.endedAt >= :since)", Object[].class);
		bind(q, f, false);
		q.setParameter("since", since, TemporalType.TIMESTAMP);
		q.setParameter("now", f.now, TemporalType.TIMESTAMP);
		List<long[]> deltas = new ArrayList<>();
		for (Object[] r : q.getResultList()) {
			long start = ((Date) r[0]).getTime();
			Date end = (Date) r[1];
			if (end == null && f.now.getTime() - start > LEAK_MS) {
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
		Table t = new Table("hour", "peak");
		DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
		LocalDateTime h = LocalDateTime.ofInstant(since.toInstant(), zone).withMinute(0).withSecond(0).withNano(0);
		LocalDateTime end = LocalDateTime.ofInstant(f.now.toInstant(), zone);
		long running = 0;
		int i = 0;
		for (; !h.isAfter(end); h = h.plusHours(1)) {
			long hourEnd = h.plusHours(1).atZone(zone).toInstant().toEpochMilli();
			// The hour starts with whatever was already up, then sees every change in it.
			long peak = running;
			for (; i < deltas.size() && deltas.get(i)[0] < hourEnd; i++) {
				running += deltas.get(i)[1];
				peak = Math.max(peak, running);
			}
			t.row(h.format(fmt), peak);
		}
		return t;
	}

	/// The clusters the data holds, for the detail pages' cluster selector.
	/// Ignores the cluster filter (it would only ever return itself).
	private Table callsClusters(EntityManager em, Filter f) {
		Filter all = new Filter(f.days, f.includeSample, null, f.now);
		List<Object[]> rows = list(em, "SELECT c.clusterName, COUNT(c) FROM CallRow c" + where("c", "c.startedAt", all)
				+ " GROUP BY c.clusterName", all, true);
		rows.sort(byCountDesc(1));
		Table t = new Table("cluster", "calls");
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
		List<Object[]> rows = list(em, "SELECT EXTRACT(YEAR FROM c.startedAt), EXTRACT(MONTH FROM c.startedAt), "
				+ "EXTRACT(DAY FROM c.startedAt), AVG(c.durationSeconds) FROM CallRow c" + where("c", "c.startedAt", f)
				+ " AND c.durationSeconds IS NOT NULL GROUP BY EXTRACT(YEAR FROM c.startedAt), "
				+ "EXTRACT(MONTH FROM c.startedAt), EXTRACT(DAY FROM c.startedAt)", f, true);
		TreeMap<String, Double> byDay = new TreeMap<>();
		for (Object[] r : rows) {
			byDay.put(dayKey(r[0], r[1], r[2]), round(((Number) r[3]).doubleValue()));
		}
		Table t = new Table("day", "average");
		for (String day : fillDays(new TreeMap<String, Long>(), f).keySet()) {
			t.row(day, byDay.get(day));
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

	private Table outcomesSummary(EntityManager em, Filter f) {
		return new Table("started", "answered", "completed", "lost").row(distinctCalls(em, f, STARTED),
				distinctCalls(em, f, ANSWERED), distinctCalls(em, f, COMPLETED), distinctCalls(em, f, LOST));
	}

	private Table outcomesDaily(EntityManager em, Filter f) {
		Map<String, Long> started = callsPerDay(em, f, STARTED);
		Map<String, Long> answered = callsPerDay(em, f, ANSWERED);
		Map<String, Long> abandoned = callsPerDay(em, f, ABANDONED);
		Table t = new Table("day", "started", "answered", "abandoned");
		for (String day : fillDays(new TreeMap<String, Long>(), f).keySet()) {
			t.row(day, started.getOrDefault(day, 0L), answered.getOrDefault(day, 0L),
					abandoned.getOrDefault(day, 0L));
		}
		return t;
	}

	/// Started → answered → completed, over the applications that report
	/// answering at all. Pooling every application would repeat the pooled
	/// answer rate's mistake: a conference bridge's calls would sit in
	/// "started" forever and drag every later stage down.
	private Table outcomesFunnel(EntityManager em, Filter f) {
		List<String> answering = new ArrayList<>(callsPerApplication(em, f, ANSWERED).keySet());
		Table t = new Table("stage", "calls");
		if (answering.isEmpty()) {
			return t;
		}
		return t.row("Started", distinctCalls(em, f, STARTED, answering))
				.row("Answered", distinctCalls(em, f, ANSWERED, answering))
				.row("Completed", distinctCalls(em, f, COMPLETED, answering));
	}

	/// Started, answered, completed and lost calls per application, with each
	/// application's own answer rate. Only applications that started a call
	/// appear: the rest publish no lifecycle at all.
	private Table outcomesByApplication(EntityManager em, Filter f) {
		Map<String, Long> started = callsPerApplication(em, f, STARTED);
		Map<String, Long> answered = callsPerApplication(em, f, ANSWERED);
		Map<String, Long> completed = callsPerApplication(em, f, COMPLETED);
		Map<String, Long> lost = callsPerApplication(em, f, LOST);
		List<String> apps = new ArrayList<>(started.keySet());
		apps.sort(Comparator.comparing((String a) -> -started.get(a)).thenComparing(a -> a));
		Table t = new Table("application", "started", "answered", "completed", "lost", "answer rate %");
		for (String app : apps) {
			long s = started.get(app);
			long a = answered.getOrDefault(app, 0L);
			t.row(app, s, a, completed.getOrDefault(app, 0L), lost.getOrDefault(app, 0L),
					s == 0 ? null : round(100.0 * a / s));
		}
		return t;
	}

	private long distinctCalls(EntityManager em, Filter f, List<String> types) {
		return distinctCalls(em, f, types, null);
	}

	/// Distinct calls with any of `types`, optionally only in `applications`.
	private long distinctCalls(EntityManager em, Filter f, List<String> types, List<String> applications) {
		TypedQuery<Long> q = em.createQuery("SELECT COUNT(DISTINCT e.callId) FROM EventRow e"
				+ where("e", "e.occurredAt", f) + " AND LOWER(e.eventType) IN :types"
				+ (applications == null ? "" : " AND e.application IN :apps"), Long.class);
		bind(q, f, true);
		q.setParameter("types", types);
		if (applications != null) {
			q.setParameter("apps", applications);
		}
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

	private Map<String, Long> callsPerDay(EntityManager em, Filter f, List<String> types) {
		return dayCounts(em, "e.occurredAt", "COUNT(DISTINCT e.callId)", "EventRow e", "e", f, types);
	}

	// ── sessions ─────────────────────────────────────────────────────────────

	/// Sessions never closed, whenever they started: a leak is a leak however
	/// old, so these ignore the window (the sample and cluster filters still apply).
	private Table sessionsSummary(EntityManager em, Filter f) {
		TypedQuery<Object[]> q = em.createQuery(
				"SELECT COUNT(c), MIN(c.startedAt) FROM CallRow c" + where("c", null, f) + " AND c.endedAt IS NULL",
				Object[].class);
		bind(q, f, false);
		Object[] r = q.getSingleResult();
		return new Table("open", "oldest").row(num(r[0]), stamp(r[1]));
	}

	private Table sessionsOpen(EntityManager em, Filter f) {
		TypedQuery<Object[]> q = em.createQuery("SELECT c.vorpalId, c.application, c.server, c.startedAt FROM CallRow c"
				+ where("c", null, f) + " AND c.endedAt IS NULL ORDER BY c.startedAt DESC", Object[].class);
		bind(q, f, false);
		q.setMaxResults(500);
		Table t = new Table("vorpal id", "application", "engine", "started");
		for (Object[] r : q.getResultList()) {
			t.row(label(r[0]), label(r[1]), label(r[2]), stamp(r[3]));
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

	private Table eventsSummary(EntityManager em, Filter f) {
		TypedQuery<Object[]> q = em.createQuery("SELECT COUNT(e), COUNT(DISTINCT e.eventType) FROM EventRow e"
				+ where("e", "e.occurredAt", f), Object[].class);
		bind(q, f, true);
		Object[] r = q.getSingleResult();
		return new Table("events", "types").row(num(r[0]), num(r[1]));
	}

	private Table eventsDaily(EntityManager em, Filter f) {
		Table t = new Table("day", "events");
		for (Map.Entry<String, Long> e : fillDays(dayCounts(em, "e.occurredAt", "COUNT(e)", "EventRow e", "e", f, null),
				f).entrySet()) {
			t.row(e.getKey(), e.getValue());
		}
		return t;
	}

	private Table eventsByType(EntityManager em, Filter f) {
		List<Object[]> rows = list(em, "SELECT e.eventType, COUNT(e) FROM EventRow e" + where("e", "e.occurredAt", f)
				+ " GROUP BY e.eventType", f, true);
		rows.sort(byCountDesc(1));
		Table t = new Table("type", "events");
		for (Object[] r : rows) {
			t.row(label(r[0]), num(r[1]));
		}
		return t;
	}

	private Table eventsByTypeApplication(EntityManager em, Filter f) {
		List<Object[]> rows = list(em, "SELECT e.eventType, e.application, COUNT(e) FROM EventRow e"
				+ where("e", "e.occurredAt", f) + " GROUP BY e.eventType, e.application", f, true);
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

	private Table riskDaily(EntityManager em, Filter f) {
		Table t = new Table("day", "assessments");
		for (Map.Entry<String, Long> e : fillDays(dayCounts(em, "r.assessedAt", "COUNT(r)", "RiskRow r", "r", f, null),
				f).entrySet()) {
			t.row(e.getKey(), e.getValue());
		}
		return t;
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
			t.row(label(r[0]), label(r[1]), stamp(r[2]), r[3] == null ? null : round(((Number) r[3]).doubleValue()),
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

	// ── query plumbing ───────────────────────────────────────────────────────

	/// The scope clause for one alias: the time window (when `timePath` is
	/// given), the sample exclusion, the cluster. Always starts " WHERE", so
	/// callers append further conditions with " AND".
	static String where(String alias, String timePath, Filter f) {
		StringBuilder w = new StringBuilder(" WHERE 1 = 1");
		if (timePath != null) {
			w.append(" AND ").append(timePath).append(" >= :since");
		}
		if (!f.includeSample) {
			w.append(" AND (").append(alias).append(".clusterName IS NULL OR ").append(alias)
					.append(".clusterName <> 'sample')");
		}
		if (f.cluster != null) {
			w.append(" AND ").append(alias).append(".clusterName = :cluster");
		}
		return w.toString();
	}

	private static void bind(TypedQuery<?> q, Filter f, boolean windowed) {
		if (windowed) {
			q.setParameter("since", f.since(), TemporalType.TIMESTAMP);
		}
		if (f.cluster != null) {
			q.setParameter("cluster", f.cluster);
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
		q.setParameter("since", since, TemporalType.TIMESTAMP);
		Long n = q.getSingleResult();
		return n == null ? 0 : n;
	}

	private Map<String, Long> dayCounts(EntityManager em, String timePath, String aggregate, String from,
			String alias, Filter f, List<String> types) {
		return dayCounts(em, timePath, aggregate, from, alias, f, types, "");
	}

	/// An aggregate per calendar day: groups on `EXTRACT` of year, month and day,
	/// which EclipseLink renders for each database.
	private Map<String, Long> dayCounts(EntityManager em, String timePath, String aggregate, String from,
			String alias, Filter f, List<String> types, String extra) {
		String y = "EXTRACT(YEAR FROM " + timePath + ")";
		String m = "EXTRACT(MONTH FROM " + timePath + ")";
		String d = "EXTRACT(DAY FROM " + timePath + ")";
		String jpql = "SELECT " + y + ", " + m + ", " + d + ", " + aggregate + " FROM " + from
				+ where(alias, timePath, f) + extra
				+ (types == null ? "" : " AND LOWER(" + alias + ".eventType) IN :types") + " GROUP BY " + y + ", " + m
				+ ", " + d;
		TypedQuery<Object[]> q = em.createQuery(jpql, Object[].class);
		bind(q, f, true);
		if (types != null) {
			q.setParameter("types", types);
		}
		Map<String, Long> byDay = new TreeMap<>();
		for (Object[] r : q.getResultList()) {
			byDay.merge(dayKey(r[0], r[1], r[2]), num(r[3]), Long::sum);
		}
		return byDay;
	}

	/// Every day of the window in order, zero where nothing happened, so a gap
	/// shows as a gap instead of the bars closing ranks.
	private Map<String, Long> fillDays(Map<String, Long> byDay, Filter f) {
		Map<String, Long> all = new LinkedHashMap<>();
		LocalDate end = LocalDate.ofInstant(f.now.toInstant(), zone);
		for (LocalDate d = LocalDate.ofInstant(f.since().toInstant(), zone); !d.isAfter(end); d = d.plusDays(1)) {
			String key = d.format(DAY);
			all.put(key, byDay.getOrDefault(key, 0L));
		}
		return all;
	}

	private Date startOfDay(Date now) {
		return Date.from(LocalDate.ofInstant(now.toInstant(), zone).atStartOfDay(zone).toInstant());
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

	private static String dayKey(Object y, Object m, Object d) {
		return String.format("%04d-%02d-%02d", num(y), num(m), num(d));
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

	private String stamp(Object o) {
		return o == null ? null : LocalDateTime.ofInstant(((Date) o).toInstant(), zone).format(STAMP);
	}

	private static Double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
