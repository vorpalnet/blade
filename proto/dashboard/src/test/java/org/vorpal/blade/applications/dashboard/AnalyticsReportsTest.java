package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
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
		// Timestamps in the fixture are UTC wall-clock; read them back the same way.
		savedZone = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
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
		reports = new AnalyticsReports(factory, ZoneOffset.UTC);
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

	private static AnalyticsReports.Table run(String name) {
		return reports.run(name, new AnalyticsReports.Filter(7, false, null, NOW));
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
				reports.run(name, new AnalyticsReports.Filter(7, sample, null, NOW));
				reports.run(name, new AnalyticsReports.Filter(1, sample, "SIPREC-03", NOW));
			}
		}
	}

	@Test
	void unknownReportIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> reports.run("calls.everything", new AnalyticsReports.Filter(7, false, null, NOW)));
	}

	@Test
	void sampleRowsAreExcludedUnlessAskedFor() {
		assertEquals(5, n(only(run("calls.summary")).get(0)));
		AnalyticsReports.Table withSample = reports.run("calls.summary",
				new AnalyticsReports.Filter(7, true, null, NOW));
		assertEquals(6, n(only(withSample).get(0)));
	}

	@Test
	void callsSummary() {
		List<Object> r = only(run("calls.summary"));
		assertEquals(Arrays.asList("calls", "today", "open", "applications"), run("calls.summary").columns);
		assertEquals(3, n(r.get(1)), "c1, c2, c6 started today");
		assertEquals(2, n(r.get(2)), "c4 (leaked) and c6 (still up) are open");
		assertEquals(3, n(r.get(3)), "conference, agent, meetings");
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
	void callsDailyFillsEmptyDays() {
		AnalyticsReports.Table t = run("calls.daily");
		assertEquals(8, t.rows.size(), "Sep 26 .. Oct 3 inclusive");
		assertEquals(Arrays.asList("2026-10-03", 3L), t.rows.get(t.rows.size() - 1));
		assertEquals(Arrays.asList("2026-09-30", 0L), t.rows.get(4));
	}

	@Test
	void concurrencyCountsOverlapAndSkipsLeaks() {
		AnalyticsReports.Table t = reports.run("calls.concurrent", new AnalyticsReports.Filter(1, false, null, NOW));
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
	void outcomesArePerApplicationAndCountCallsOnce() {
		AnalyticsReports.Table t = run("outcomes.by-application");
		assertEquals(2, t.rows.size(), "meetings started nothing and is absent");
		assertEquals(Arrays.asList("agent", 2L, 2L, 1L, 0L, 100.0), t.rows.get(0),
				"c3 answered AND connected, counted once");
		assertEquals(Arrays.asList("conference", 1L, 0L, 0L, 0L, 0.0), t.rows.get(1));
		AnalyticsReports.Table funnel = run("outcomes.funnel");
		assertEquals(Arrays.asList("Started", 2L), funnel.rows.get(0),
				"only applications that report answering: agent's c3 and c6, not conference's c1");
		assertEquals(Arrays.asList("Answered", 2L), funnel.rows.get(1));
		assertEquals(Arrays.asList("Completed", 1L), funnel.rows.get(2));
	}

	@Test
	void openSessionsIgnoreTheWindowNewestFirst() {
		AnalyticsReports.Table t = reports.run("sessions.open", new AnalyticsReports.Filter(1, false, null, NOW));
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
	void medianOfEvenAndOdd() {
		assertEquals(2.0, AnalyticsReports.median(Arrays.asList(1.0, 2.0, 3.0)));
		assertEquals(2.5, AnalyticsReports.median(Arrays.asList(1.0, 2.0, 3.0, 4.0)));
	}
}
