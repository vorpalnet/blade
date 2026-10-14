package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Every dashboard report, run through EclipseLink against a real database.
///
/// HSQLDB in memory, with the five reporting views stood up as tables
/// (`hsqldb-views.sql`) and a handful of calls chosen to exercise each rule:
/// sample rows dropped, a lifecycle with both answered and connected counted
/// once, a conference application that never answers, a leaked session, two
/// overlapping calls. "Now" is pinned at 2026-10-03 12:00 UTC.
///
/// This is also the portability check that matters most: it proves the JPQL,
/// including EclipseLink's `EXTRACT` grouping, parses and runs on a database
/// that is not Oracle.
class AnalyticsReportsTest {

	private static final Date NOW = Date.from(Instant.parse("2026-10-03T12:00:00Z"));
	private static TimeZone savedZone;
	private static Connection keepAlive;
	private static EntityManagerFactory factory;
	private static AnalyticsReports reports;

	@BeforeAll
	static void setUp() throws Exception {
		// The fixture's timestamps are UTC wall-clock, as the analytics service
		// writes them. The JVM runs six hours off UTC, so a report that reads or
		// binds a time in the JVM's zone instead of UTC lands in the wrong hour.
		savedZone = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
		// HSQLDB drops an in-memory database when its last connection closes.
		keepAlive = DriverManager.getConnection("jdbc:hsqldb:mem:blade-dashboard", "sa", "");
		try (InputStream in = AnalyticsReportsTest.class.getResourceAsStream("/hsqldb-views.sql");
				Statement s = keepAlive.createStatement()) {
			StringBuilder sql = new StringBuilder();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.trim().startsWith("--")) {
					sql.append(line).append('\n');
				}
			}
			for (String stmt : sql.toString().split(";")) {
				if (!stmt.trim().isEmpty()) {
					s.execute(stmt);
				}
			}
		}
		factory = Persistence.createEntityManagerFactory("BladeDashboardTest");
		reports = new AnalyticsReports(factory);
	}

	@AfterAll
	static void tearDown() throws Exception {
		factory.close();
		try (Statement s = keepAlive.createStatement()) {
			s.execute("SHUTDOWN");
		}
		keepAlive.close();
		TimeZone.setDefault(savedZone);
	}

	/// A scope viewed from UTC.
	private static AnalyticsReports.Filter filter(int days, boolean sample, String cluster) {
		return new AnalyticsReports.Filter(days, sample, cluster, NOW, ZoneOffset.UTC);
	}

	private static AnalyticsReports.Table run(String name) {
		return reports.run(name, filter(7, false, null));
	}

	private static List<Object> only(AnalyticsReports.Table t) {
		assertEquals(1, t.rows.size(), "one row");
		return t.rows.get(0);
	}

	private static long n(Object o) {
		return ((Number) o).longValue();
	}

	@Test
	void everyReportRunsInEveryScope() {
		for (String name : AnalyticsReports.NAMES) {
			for (boolean sample : new boolean[] { false, true }) {
				reports.run(name, filter(7, sample, null));
				reports.run(name, filter(1, sample, "east-1"));
			}
		}
	}

	@Test
	void unknownReportIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> reports.run("calls.everything", filter(7, false, null)));
	}

	@Test
	void sampleRowsAreExcludedUnlessAskedFor() {
		assertEquals(5, n(only(run("calls.summary")).get(0)));
		AnalyticsReports.Table withSample = reports.run("calls.summary",
				filter(7, true, null));
		assertEquals(6, n(only(withSample).get(0)));
	}

	@Test
	void callsSummary() {
		List<Object> r = only(run("calls.summary"));
		assertEquals(Arrays.asList("calls", "today", "open", "leaked", "applications"), run("calls.summary").columns);
		assertEquals(3, n(r.get(1)), "c1, c2, c6 started today");
		assertEquals(1, n(r.get(2)), "c6 is still up");
		assertEquals(1, n(r.get(3)), "c4 never closed");
		assertEquals(4, n(r.get(4)), "conference, agent, meetings, transfer");
		List<Object> strict = only(reports.run("calls.summary", filter(7, false, null).tuned(10 * 60_000L,
				AnalyticsReports.SPLIT_MS, AnalyticsReports.SAMPLE_FRESH_MS)));
		assertEquals(Arrays.asList(0L, 2L), Arrays.asList(n(strict.get(2)), n(strict.get(3))),
				"leakHours from the settings: with a 10-minute limit c6, up 30 minutes, is a leak too");
	}

	@Test
	void callsByOutcomeAreGoodToBad() {
		AnalyticsReports.Table t = run("calls.by-outcome");
		assertEquals(Arrays.asList("hour", "completed", "abandoned", "declined", "failed", "dropped"), t.columns);
		long ended = 0, completed = 0;
		for (List<Object> row : t.rows) {
			for (int i = 1; i < row.size(); i++) {
				ended += n(row.get(i));
			}
			completed += n(row.get(1));
		}
		assertEquals(1, ended, "only c3 has an end and a call row; a call with no end is in no part");
		assertEquals(1, completed, "c3, completed (its decline by transfer gives way)");
		assertEquals("day", month("calls.by-outcome").columns.get(0), "past a week, per day");
	}

	@Test
	void callsByAreaPlaceTheCallerWithoutTheNumber() {
		AnalyticsReports.Table t = run("calls.by-area");
		assertEquals(Arrays.asList("area", "completed", "abandoned", "declined", "failed", "dropped"), t.columns);
		assertEquals(Arrays.asList(Arrays.asList("312", 1L, 0L, 0L, 0L, 0L)), t.rows,
				"c3, completed, placed by the area the framework recorded, not by parsing its caller key");
	}

	@Test
	void originsAreCountedPerAddressWithTheLastCall() {
		AnalyticsReports.Table t = reports.origins(filter(7, false, null), Date.from(Instant.parse("2026-10-03T00:00:00Z")));
		assertEquals(Arrays.asList("origin", "calls", "last call"), t.columns);
		assertEquals(2, t.rows.size(), "c1 and c2 started since the 3rd; c3 on the 2nd is before it");
		for (List<Object> r : t.rows) {
			assertEquals(1L, r.get(1));
			assertTrue(r.get(2) instanceof Date);
		}
	}

	@Test
	void failuresAreTheLegsServersRefusedInTheWindow() {
		AnalyticsReports.Table t = reports.failures(filter(7, false, null), Date.from(Instant.parse("2026-10-03T11:00:00Z")));
		assertEquals(Arrays.asList("destination", "responder", "status", "not sent", "calls", "last", "retry after"),
				t.columns);
		long refused = 0;
		for (List<Object> r : t.rows) {
			refused += (Long) r.get(4);
			if (Long.valueOf(503).equals(r.get(2))) {
				assertEquals(2L, r.get(4), "two 503s from one server in the window; the 09:00 one is before it");
				assertEquals(60L, r.get(6), "the longest Retry-After asked");
			}
			if (Long.valueOf(500).equals(r.get(2))) {
				assertEquals(Boolean.TRUE, r.get(3), "an INVITE the transport refused");
			}
		}
		assertEquals(5, refused, "503 twice, 408, 486, the unsent 500: every branch failure, not the call's callDeclined");
	}

	@Test
	void anAreaIsTheAreaCodeOrTheCountryPrefix() {
		assertEquals("646", AnalyticsReports.area("sip:+16465550100@carrier.example"));
		assertEquals("646", AnalyticsReports.area("sip:16465550100@carrier.example"));
		assertEquals("646", AnalyticsReports.area("tel:6465550100"));
		assertEquals("646", AnalyticsReports.area("sips:6465550100;user=phone@x"));
		assertEquals("+442", AnalyticsReports.area("sip:+442071234567@carrier.example"));
		assertNull(AnalyticsReports.area("sip:1234@pbx"), "an extension");
		assertNull(AnalyticsReports.area("sip:anonymous@anonymous.invalid"));
		assertNull(AnalyticsReports.area("sip:0465550100@x"), "no area code starts 0 or 1");
		assertNull(AnalyticsReports.area(null));
	}

	@Test
	void heapIsEachServersPeakPerHour() {
		AnalyticsReports.Table t = run("platform.heap");
		assertEquals(Arrays.asList("hour", "server", "peak heap %"), t.columns);
		assertEquals(Arrays.asList(Arrays.asList("2026-10-03 10:00", "east-1/AdminServer", 40L),
				Arrays.asList("2026-10-03 10:00", "east-1/engine0", 78L),
				Arrays.asList("2026-10-03 11:00", "west-1/engine0", 44L)), t.rows,
				"engine0's 10:00 hour peaks at its 78, not its 61; two domains, so each server is named with its own");
		AnalyticsReports.Table east = reports.run("platform.heap", filter(7, false, "east-1"));
		assertEquals(Arrays.asList("2026-10-03 10:00", "engine0", 78L), east.rows.get(1), "one domain: the server alone");
		AnalyticsReports.Table cpu = reports.run("platform.cpu", filter(7, false, "east-1"));
		assertEquals(Arrays.asList("hour", "server", "peak cpu %"), cpu.columns);
		assertEquals(Arrays.asList("2026-10-03 10:00", "engine0", 82L), cpu.rows.get(1), "the busy minute, 82%, not 35%");
	}

	@Test
	void callsNowCountsWhatIsUpAndNotTheLeak() {
		assertEquals(Arrays.asList("now", "peak last hour", "peak at"), run("calls.now").columns);
		assertEquals(Arrays.asList(1L, 1L, "2026-10-03 11:30:00"), only(run("calls.now")),
				"c6, up since 11:30; c4 has been open since Sep 29, a leak, not a call");
	}

	@Test
	void callsByHourOfDayAndWeekday() {
		AnalyticsReports.Table hours = run("calls.hour-of-day");
		assertEquals(24, hours.rows.size());
		assertEquals(2, n(hours.rows.get(8).get(1)), "c1 and c2 at 08:00");
		AnalyticsReports.Table week = run("calls.weekday");
		assertEquals(Arrays.asList("Sat", 3L), week.rows.get(5), "2026-10-03 is a Saturday: c1, c2, c6");
		assertEquals(168, run("calls.heatmap").rows.size(), "the whole 7 x 24 grid");
	}

	@Test
	void theWindowIsCutInUtcWhateverTheJvmZone() {
		// Since = 2026-10-02 12:00 UTC. c3 began at 09:00 UTC, three hours
		// before: out. Bound in the JVM's zone (Chicago), the edge would read
		// 07:00 and let c3 in.
		assertEquals(3, n(only(reports.run("calls.summary", filter(1, false, null))).get(0)), "c1, c2, c6");
	}

	@Test
	void daysAndHoursAreTheViewersOwn() {
		ZoneId kolkata = ZoneId.of("Asia/Kolkata"); // UTC+5:30, half an hour off any UTC hour
		AnalyticsReports.Table hours = reports.run("calls.hour-of-day",
				new AnalyticsReports.Filter(7, false, null, NOW, kolkata));
		assertEquals(2, n(hours.rows.get(13).get(1)), "c1 and c2: 08:00 UTC is 13:30 in Kolkata");
		assertEquals(1, n(hours.rows.get(23).get(1)), "c4: 18:00 UTC is 23:30");
		assertEquals(0, n(hours.rows.get(8).get(1)));

		// Now is 01:00 on Oct 4 in Auckland (UTC+13): "today" there began at
		// 11:00 UTC, and only c6 (11:30 UTC) started since.
		ZoneId auckland = ZoneId.of("Pacific/Auckland");
		assertEquals(1, n(only(reports.run("calls.summary", new AnalyticsReports.Filter(7, false, null, NOW, auckland)))
				.get(1)));
		AnalyticsReports.Table days = reports.run("calls.daily", new AnalyticsReports.Filter(7, false, null, NOW, auckland));
		assertEquals(Arrays.asList("2026-10-04", 1L), days.rows.get(days.rows.size() - 1), "c6, after midnight there");

		AnalyticsReports.Table open = reports.run("sessions.summary", new AnalyticsReports.Filter(1, false, null, NOW, kolkata));
		assertEquals("2026-09-29 23:30:00", only(open).get(1), "the leak's start, printed in the viewer's zone");
	}

	@Test
	void callsDailyFillsEmptyDays() {
		AnalyticsReports.Table t = run("calls.daily");
		assertEquals(7, t.rows.size(), "seven whole days, Sep 27 .. Oct 3, not a partial Sep 26 before them");
		assertEquals(Arrays.asList("2026-10-03", 3L), t.rows.get(t.rows.size() - 1));
		assertEquals(Arrays.asList("2026-09-30", 0L), t.rows.get(3));
	}

	@Test
	void concurrencyCountsOverlapAndSkipsLeaks() {
		AnalyticsReports.Table t = reports.run("calls.concurrent", filter(1, false, null));
		long at8 = -1, at11 = -1, at10 = -1;
		for (List<Object> row : t.rows) {
			switch ((String) row.get(0)) {
			case "2026-10-03 08:00": at8 = n(row.get(1)); break;
			case "2026-10-03 10:00": at10 = n(row.get(1)); break;
			case "2026-10-03 11:00": at11 = n(row.get(1)); break;
			default: break;
			}
		}
		assertEquals(2, at8, "c1 and c2 overlap");
		assertEquals(0, at10, "the Sep 29 leak does not hold the count up");
		assertEquals(1, at11, "c6 is up");
	}

	@Test
	void durations() {
		assertEquals(Arrays.asList(70.0, 60.0, 120.0), only(run("duration.summary")));
		AnalyticsReports.Table byApp = run("duration.by-application");
		assertEquals(Arrays.asList("agent", 2L, 30.0, 30.0, 30.0), byApp.rows.get(0),
				"agent first by calls (tie broken by name); only the ended call has a duration");
		assertEquals(Arrays.asList("conference", 2L, 90.0, 90.0, 120.0), byApp.rows.get(1));
	}

	@Test
	void outcomesAreHowCallsEndedEachCallOnce() {
		assertEquals(Arrays.asList(Arrays.asList("completed", 2L), Arrays.asList("abandoned", 0L),
				Arrays.asList("declined", 0L), Arrays.asList("failed", 0L), Arrays.asList("dropped", 0L)),
				run("outcomes.ended").rows,
				"c3 and c9 completed; transfer declined c3 before agent took it, and completed wins");
		AnalyticsReports.Table byApp = run("outcomes.by-application");
		assertEquals(Arrays.asList("application", "completed", "abandoned", "declined", "failed", "dropped"),
				byApp.columns);
		assertEquals(Arrays.asList("agent", 2L, 0L, 0L, 0L, 0L), byApp.rows.get(0));
		assertEquals(Arrays.asList("transfer", 0L, 0L, 1L, 0L, 0L), byApp.rows.get(1),
				"per application, transfer's refusal of c3 still counts");
		assertEquals(2L, run("outcomes.daily").rows.stream().mapToLong(row -> n(row.get(1))).sum());
	}

	@Test
	void anEndIsJudgedByWhatCameBeforeIt() {
		AnalyticsReports.Filter sample = filter(7, true, "sample");
		assertEquals(Arrays.asList(Arrays.asList("completed", 0L), Arrays.asList("abandoned", 1L),
				Arrays.asList("declined", 1L), Arrays.asList("failed", 1L), Arrays.asList("dropped", 2L)),
				reports.run("outcomes.ended", sample).rows,
				"c34 abandoned (its 487 came from the cancel); c31 declined after a callee's 486; c30 failed"
						+ " after a 408 nobody answered; c32 abandoned after its answer and c33 expired out of"
						+ " step, both dropped");
		AnalyticsReports.Table byApp = reports.run("outcomes.by-application", sample);
		assertEquals(Arrays.asList("agent", 0L, 1L, 0L, 0L, 2L), byApp.rows.get(0));
		assertEquals(Arrays.asList("transfer", 0L, 0L, 1L, 1L, 0L), byApp.rows.get(1));
	}

	@Test
	void openSessionsIgnoreTheWindowNewestFirst() {
		AnalyticsReports.Table t = reports.run("sessions.open", filter(1, false, null));
		assertEquals(2, t.rows.size(), "the Sep 29 leak shows even in a one-day window");
		assertEquals("000000C6", t.rows.get(0).get(0));
		assertEquals("000000C4", t.rows.get(1).get(0));
		assertEquals("2026-09-29 18:00:00", t.rows.get(1).get(3));
	}

	@Test
	void platform() {
		assertEquals(Arrays.asList(1L, 1L), only(run("platform.summary")));
		assertTrue(run("platform.versions").rows.stream().noneMatch(r -> "2.9.6".equals(r.get(1))),
				"the sample generator's version stays out");
	}

	@Test
	void risk() {
		List<Object> s = only(run("risk.summary"));
		assertEquals(Arrays.asList(2L, 3L, 1L, 0.55), s);
		AnalyticsReports.Table bands = run("risk.by-band");
		assertEquals(Arrays.asList("SUSPECT", "WATCH", "CLEAR"),
				Arrays.asList(bands.rows.get(0).get(0), bands.rows.get(1).get(0), bands.rows.get(2).get(0)),
				"worst band first, top to bottom");
	}

	@Test
	void highRiskCallsWorstFirstWithoutClear() {
		AnalyticsReports.Table t = run("highrisk.calls");
		assertEquals(2, t.rows.size(), "CLEAR and sample rows are out");
		assertEquals(Arrays.asList("SUSPECT", "c1"), t.rows.get(0).subList(0, 2));
		assertEquals(Arrays.asList("WATCH", "c2"), t.rows.get(1).subList(0, 2));
	}

	@Test
	void conversationCountsUtterancesOnly() {
		assertEquals(Arrays.asList(3L, 2L), only(run("conversation.summary")), "callStarted rows are not speech");
		AnalyticsReports.Table byIntent = run("conversation.by-intent");
		assertEquals(Arrays.asList("weather", 2L), byIntent.rows.get(0));
		assertFalse(byIntent.rows.isEmpty());
	}

	@Test
	void callsByApplicationCountsEveryApplicationOnACall() {
		AnalyticsReports.Table t = run("calls.by-application");
		assertEquals(Arrays.asList("agent", 2L), t.rows.get(0));
		assertEquals(Arrays.asList("conference", 2L), t.rows.get(1));
		assertEquals(Arrays.asList("meetings", 1L), t.rows.get(2), "a row with no events still counts");
		AnalyticsReports.Table month = month("calls.by-application");
		assertEquals(Arrays.asList("agent", 3L), month.rows.get(0),
				"c7's row names conference, yet agent published about it too");
	}

	@Test
	void healthCoverageNamesWhatEachApplicationNeverSends() {
		AnalyticsReports.Table t = month("health.coverage");
		assertEquals(Arrays.asList("application", "calls", "started", "answered", "ended", "last event", "gaps"),
				t.columns);
		assertEquals(Arrays.asList("agent", 3L, 3L, 3L, 2L, "2026-10-03 11:30:01", "none"), t.rows.get(0));
		assertEquals(Arrays.asList("conference", 3L, 2L, 0L, 0L), t.rows.get(1).subList(0, 5));
		assertEquals("never sends answered; never sends an end", t.rows.get(1).get(6));
		assertEquals(Arrays.asList("irouter", 1L, 0L, 0L, 0L), t.rows.get(2).subList(0, 5));
		assertEquals("none (routes only)", t.rows.get(2).get(6), "a proxy that routed and left is not a gap");
		assertEquals(Arrays.asList("meetings", 1L, 0L, 0L, 0L), t.rows.get(4).subList(0, 5),
				"a call row naming meetings counts, though meetings sent nothing about it");
		assertEquals("never sends started; never sends answered; never sends an end", t.rows.get(4).get(6));
		List<Object> transfer = t.rows.stream().filter(row -> "transfer".equals(row.get(0))).findFirst().get();
		assertEquals("none", transfer.get(6), "transfer only declined: it owed no answer");
	}

	@Test
	void healthChainsAndSplitCalls() {
		AnalyticsReports.Table chains = month("health.chains");
		assertTrue(chains.rows.contains(Arrays.asList("agent \u00b7 conference", 1L)));
		AnalyticsReports.Table split = month("health.split-calls");
		assertEquals(1, split.rows.size());
		assertEquals(Arrays.asList("000000C7", "2026-09-20 10:00:00", "2026-09-20 10:00:03", 3.0, "conference",
				"listener"), split.rows.get(0));
		assertEquals(Arrays.asList(6L, 3L, 1L, 3L, 3L, 4L, 1L), only(month("health.summary")),
				"irouter and transfer are not gaps; c3 reached transfer, c8 irouter and listener; 3 dropped (the Sep 1 sample is outside the window), 4 refused by engine0's container, 1 routed past "
						+ "an undeployed application");
		assertTrue(run("health.split-calls").rows.isEmpty(), "outside the 7-day window");
	}

	@Test
	void publishingSetsDroppedEventsBesideWhatArrived() {
		AnalyticsReports.Table t = run("health.publishing");
		assertEquals(Arrays.asList("application", "invites received", "calls recorded", "events published",
				"publishes failed", "sip errors", "restarts", "sessions held", "last sample"), t.columns);
		assertEquals(Arrays.asList("meetings", 6L, 1L, 52L, 3L, 1L, 1L, 2L, "2026-10-03 10:01:00"), t.rows.get(0),
				"most failures first; sessions held as of the latest sample");
		assertEquals(Arrays.asList("agent", 2L, 2L, 8L, 0L, 0L, 0L, null), t.rows.get(1).subList(0, 8),
				"no reading, not zero");
		AnalyticsReports.Table daily = run("health.publishing-daily");
		assertEquals(Arrays.asList("2026-10-03", 52L, 3L), daily.rows.get(daily.rows.size() - 1));
		assertTrue(reports.run("health.publishing", filter(7, false, "east-1")).rows
				.size() == 2, "a cluster choice does not break the sample queries, nor filter them");
	}

	@Test
	void serversShowWhatTheContainerRefusedAndWhatIsStillUp() {
		AnalyticsReports.Table t = reports.run("platform.servers", filter(7, false, "east-1"));
		assertEquals(2, t.rows.size(), "east-1's two servers; west-1's engine0 is another domain's");
		assertEquals(Arrays.asList("east-1", "AdminServer", "RUNNING", 0L, 0L, 0L, null, null),
				t.rows.get(0).subList(0, 8), "no SIP container: no session readings, not zeros");
		List<Object> engine = t.rows.get(1);
		assertEquals(Arrays.asList("east-1", "engine0", "RUNNING", 800L, 4L, 11L, 9L, 0L), engine.subList(0, 8),
				"9 application sessions alive with no SIP session under them");
		assertEquals(78L, n(engine.get(9)), "peak heap");
		assertEquals(Arrays.asList(250L, 2L, 1L, 0L), engine.subList(12, 16).stream().map(o -> n(o))
				.collect(java.util.stream.Collectors.toList()), "routed, no rule matched, past undeployed, loops");
		assertEquals(null, t.rows.get(0).get(12), "the AdminServer runs no App Router");
		AnalyticsReports.Table all = run("platform.servers");
		assertEquals(Arrays.asList("west-1", "engine0"), all.rows.get(2).subList(0, 2),
				"two domains' engine0s stay two rows");
		AnalyticsReports.Table hourly = run("platform.servers-hourly");
		assertEquals(Arrays.asList("2026-10-03 10:00", 800L, 4L), hourly.rows.get(0).subList(0, 3));
	}

	@Test
	void changesAreMarkedInOrderOnTheChartsBuckets() {
		AnalyticsReports.Table t = reports.run("changes", filter(7, false, "east-1"));
		assertEquals(Arrays.asList("bucket", "at", "change", "server", "what", "file", "failed on"), t.columns);
		assertEquals(3, t.rows.size(), "east-1's save, push and restart; west-1's push is another domain's");
		assertEquals(Arrays.asList("2026-10-03 09:00", Instant.parse("2026-10-03T09:58:00Z").toEpochMilli(),
				"config saved", "AdminServer", "operator1 · saved", "irouter.json", null), t.rows.get(0));
		assertEquals(Arrays.asList("config pushed", "AdminServer", "irouter", "irouter.json", "[\"engine1\"]"),
				t.rows.get(1).subList(2, 7));
		assertEquals(Arrays.asList("2026-10-03 10:00", "application restarted", "engine0", "meetings"),
				Arrays.asList(t.rows.get(2).get(0), t.rows.get(2).get(2), t.rows.get(2).get(3), t.rows.get(2).get(4)));
		assertEquals("2026-10-03", reports.run("changes", filter(30, false, null)).rows.get(0).get(0),
				"past a week, the day");
	}

	// ── who sees what ─────────────────────────────────────────────────────

	private static DashboardGrant grant(String platform, String tenants, String departments, boolean content) {
		DashboardGrant g = new DashboardGrant();
		g.setPlatform(list(platform));
		g.setTenants(list(tenants));
		if (departments != null) {
			g.setDepartments(list(departments));
		}
		g.setContent(content);
		return g;
	}

	private static List<String> list(String csv) {
		return csv == null || csv.isEmpty() ? new java.util.ArrayList<>() : Arrays.asList(csv.split(","));
	}

	private static AnalyticsReports.Filter as(DashboardGrant... grants) {
		return filter(7, false, null).limitedTo(Access.of(Arrays.asList(grants)));
	}

	/// The operator sees the platform, never a tenant's calls.
	@Test
	void theOperatorSeesServersButNoCalls() {
		AnalyticsReports.Filter matellio = as(grant("*", "", null, false));
		assertEquals(0L, n(only(reports.run("calls.summary", matellio)).get(0)));
		assertEquals(0L, n(only(reports.run("events.summary", matellio)).get(0)));
		assertEquals(3, reports.run("platform.servers", matellio).rows.size(), "every domain's servers");
	}

	/// A tenant sees its own calls, wherever they ran, and not the platform.
	@Test
	void aTenantSeesItsCallsAndNotThePlatform() {
		AnalyticsReports.Filter uhg = as(grant("", "uhg", null, false));
		assertEquals(2L, n(only(reports.run("calls.summary", uhg)).get(0)), "c3 and c6, not the conference's");
		assertEquals(6L, n(only(reports.run("events.summary", uhg)).get(0)), "e2..e7");
		assertEquals(0, reports.run("platform.servers", uhg).rows.size(), "no other tenant's load");
		assertThrows(SecurityException.class, () -> reports.run("highrisk.calls", uhg),
				"individual calls need content");
	}

	/// A department sees only the calls it took part in, and only its own part:
	/// c6 began in Sales and was answered in Radiology.
	@Test
	void aDepartmentSeesOnlyItsOwnPartOfACall() {
		AnalyticsReports.Filter sales = as(grant("", "uhg", "sales", true));
		assertEquals(1L, n(only(reports.run("calls.summary", sales)).get(0)), "c6 only: Sales never touched c3");
		assertEquals(1L, n(only(reports.run("events.summary", sales)).get(0)), "c6's start, not Radiology's answer");
		assertEquals(0L, n(only(reports.run("conversation.summary", sales)).get(0)), "c3's words were Radiology's");

		AnalyticsReports.Filter radiology = as(grant("", "uhg", "radiology", true));
		assertEquals(2L, n(only(reports.run("calls.summary", radiology)).get(0)), "c3, and c6 once transferred");
		assertEquals(5L, n(only(reports.run("events.summary", radiology)).get(0)), "c3's four and c6's answer");
	}

	/// A caller's account is shown only where justified: a department finds a
	/// caller only on the calls it took part in, and searching takes content.
	@Test
	void aDepartmentFindsACallerOnlyOnItsOwnCalls() {
		AnalyticsReports.Filter radiology = as(grant("", "uhg", "radiology", true));
		assertEquals(2L, n(only(reports.run("calls.summary", radiology.forAccount("jeff"))).get(0)), "c3 and c6");

		AnalyticsReports.Filter sales = as(grant("", "uhg", "sales", true));
		assertEquals(1L, n(only(reports.run("calls.summary", sales.forAccount("jeff"))).get(0)),
				"c6 only: Sales never learns jeff called Radiology on c3");
		assertEquals(0L, n(only(reports.run("calls.summary", sales.forAccount("pat"))).get(0)),
				"pat called Radiology alone");
		assertEquals(1L, n(only(reports.run("events.summary", sales.forAccount("jeff"))).get(0)),
				"and on c6, only Sales's own part");

		AnalyticsReports.Filter counts = as(grant("", "uhg", "radiology", false));
		assertThrows(SecurityException.class, () -> reports.run("calls.summary", counts.forAccount("jeff")),
				"looking up a caller takes content");
	}

	/// Grants add up: a person in two groups sees what either shows.
	@Test
	void grantsAddUp() {
		AnalyticsReports.Filter both = as(grant("", "uhg", "sales", false), grant("east-1", "", null, false));
		assertEquals(1L, n(only(reports.run("calls.summary", both)).get(0)));
		assertEquals(2, reports.run("platform.servers", both).rows.size());
		assertEquals(0L, n(only(reports.run("calls.summary", as())).get(0)), "no grant, nothing");
	}

	/// The board's view of a domain it cannot read live: each server's latest
	/// sample, if it is recent. east-1's samples are two hours old by NOW.
	@Test
	void theLatestSampleStandsForAServerItCannotRead() {
		AnalyticsReports.Table t = run("platform.latest");
		assertEquals(1, t.rows.size(), "only west-1's engine0 was sampled in the last three minutes");
		assertEquals(Arrays.asList("west-1", "engine0", "BEA_ENGINE_TIER_CLUST", "RUNNING", 44L),
				t.rows.get(0).subList(0, 5));
		assertEquals(0, reports.run("platform.latest", filter(7, false, "east-1")).rows.size());
	}

	@Test
	void applicationCountersAndTimingsKeepTheirHistory() {
		assertEquals(Arrays.asList(2L, 2L, 1L, 6L), only(run("metrics.summary")));
		AnalyticsReports.Table counters = run("metrics.counters");
		assertEquals(Arrays.asList("agent", "sip.requests.INVITE", 1L), counters.rows.get(0).subList(0, 3));
		assertEquals(Arrays.asList("meetings", "sip.requests.INVITE", 5L), counters.rows.get(1).subList(0, 3),
				"most counted first within an application");
		assertEquals(Arrays.asList("meetings", "ears.failures", 3L, "2026-10-03 10:01:00"), counters.rows.get(2));
		assertEquals(Arrays.asList("meetings", "turn.latency", 6L, 1500.0, "2026-10-03 10:01:00"),
				only(run("metrics.timings")), "(2400 + 6600) / 6");
	}

	@Test
	void eventActivityLeavesTheSamplesOut() {
		assertTrue(run("events.by-type").rows.stream().noneMatch(r -> "serverSampled".equals(r.get(0))),
				"the samples have pages of their own");
	}

	@Test
	void agentVerdictsAgainstTheWorstBandReached() {
		AnalyticsReports.Table verdicts = run("risk.verdicts");
		assertEquals(Arrays.asList("legitimate", "served", 2L, 0L, 1L), verdicts.rows.get(0));
		assertEquals(Arrays.asList("scam", "ended", 2L, 1L, 1L), verdicts.rows.get(1));
		AnalyticsReports.Table grid = run("risk.calibration");
		assertEquals(Arrays.asList("worst band", "legitimate", "scam"), grid.columns);
		assertEquals(Arrays.asList("SUSPECT", 0L, 1L), grid.rows.get(0), "worst band first");
		assertEquals(Arrays.asList("WATCH", 0L, 1L), grid.rows.get(1));
		assertEquals(Arrays.asList("CLEAR", 1L, 0L), grid.rows.get(2));
		assertEquals(Arrays.asList("(none)", 1L, 0L), grid.rows.get(3), "judged, never scored");
	}

	private static AnalyticsReports.Table month(String name) {
		return reports.run(name, filter(30, false, null));
	}

	@Test
	void medianOfEvenAndOdd() {
		assertEquals(2.0, AnalyticsReports.median(Arrays.asList(1.0, 2.0, 3.0)));
		assertEquals(2.5, AnalyticsReports.median(Arrays.asList(1.0, 2.0, 3.0, 4.0)));
	}
}
