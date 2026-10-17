package org.vorpal.blade.applications.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.vorpal.blade.framework.v3.jdbc.Dialect;

/// Every dashboard report against a real Oracle, MySQL or SQL Server database
/// built from the shipped scripts, `services/analytics/sql/<Dialect>-database-schema.sql`
/// and `<Dialect>-analytics-views.sql`.
///
/// [AnalyticsReportsTest] proves the report queries on HSQLDB, with the views
/// stood up as tables. That cannot prove the views: each database's script
/// extracts the JSON its own way, and a view that compiles can still read a
/// field wrong. This test writes raw rows into the four tables, events whose
/// payloads carry every field some view reads, and lets the database's own
/// views turn them into what the reports read.
///
/// **Opt-in, because CI runs offline.** Set `BLADE_REPORTS_URL`,
/// `BLADE_REPORTS_USER` and `BLADE_REPORTS_PASSWORD` to a scratch database
/// with both scripts applied. It refuses a database holding any row but its
/// own fixture, so it cannot be pointed at real data by mistake, and it
/// removes the fixture when it finishes.
///
/// Every report's output lands in `target/reports-<dialect>.txt`. The same
/// fixture on two databases must give the same file; `diff` them to prove the
/// views agree.
@EnabledIfEnvironmentVariable(named = "BLADE_REPORTS_URL", matches = ".+")
class ReportsOnDatabaseTest {

	private static final String URL = System.getenv("BLADE_REPORTS_URL");
	private static final String USER = System.getenv("BLADE_REPORTS_USER");
	private static final String PASSWORD = System.getenv("BLADE_REPORTS_PASSWORD");

	private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
	private static final String CLUSTER = "fixture";

	/// The fixture's keys: a range no natural-key hash is likely to land in.
	private static final long BASE = 7_700_000_000_000_000_000L;
	private static final long AGENT = BASE + 1, TRANSFER = BASE + 2, DASHBOARD = BASE + 3;

	private static Connection jdbc;
	private static Dialect dialect;
	private static EntityManagerFactory factory;
	private static long nextEvent = BASE + 1000;

	@BeforeAll
	static void setUp() throws Exception {
		jdbc = DriverManager.getConnection(URL, USER, PASSWORD);
		dialect = Dialect.of(jdbc);
		removeFixture();
		for (String table : new String[] { "events", "session_keys", "sessions", "applications" }) {
			try (Statement s = jdbc.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
				rs.next();
				if (rs.getLong(1) > 0) {
					fail(table + " holds rows that are not this test's fixture: point BLADE_REPORTS_URL at a scratch database");
				}
			}
		}
		seed();
		Map<String, Object> properties = new HashMap<>();
		properties.put("javax.persistence.jdbc.url", URL);
		properties.put("javax.persistence.jdbc.user", USER);
		properties.put("javax.persistence.jdbc.password", PASSWORD);
		properties.put("javax.persistence.jdbc.driver", DriverManager.getDriver(URL).getClass().getName());
		factory = Persistence.createEntityManagerFactory("BladeDashboardTest", properties);
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (factory != null) {
			factory.close();
		}
		if (jdbc != null) {
			removeFixture();
			jdbc.close();
		}
	}

	@Test
	void everyReportRunsAndIsWrittenDown() throws IOException {
		AnalyticsReports reports = new AnalyticsReports(factory);
		Date now = Date.from(NOW);
		Object[][] scopes = { { 7, false, null }, { 1, false, null }, { 30, true, null }, { 7, false, CLUSTER } };
		StringBuilder out = new StringBuilder();
		List<String> failed = new ArrayList<>();
		for (String name : AnalyticsReports.NAMES) {
			for (Object[] scope : scopes) {
				String label = name + " · " + scope[0] + " days" + (Boolean.TRUE.equals(scope[1]) ? " · sample" : "")
						+ (scope[2] == null ? "" : " · " + scope[2]);
				try {
					AnalyticsReports.Table t = reports.run(name, new AnalyticsReports.Filter((Integer) scope[0],
							(Boolean) scope[1], (String) scope[2], now, ZoneOffset.UTC));
					out.append("== ").append(label).append('\n').append(String.join(" | ", t.columns)).append('\n');
					for (List<Object> row : t.rows) {
						List<String> cells = new ArrayList<>();
						for (Object cell : row) {
							cells.add(cell(name, cell));
						}
						out.append(String.join(" | ", cells)).append('\n');
					}
				} catch (RuntimeException e) {
					failed.add(label + ": " + e);
				}
			}
		}
		Path file = Paths.get("target", "reports-" + dialect.name().toLowerCase(Locale.ROOT) + ".txt");
		Files.createDirectories(file.getParent());
		Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
		assertEquals(new ArrayList<String>(), failed, "reports that failed on " + dialect);
	}

	/// One value as text two databases can agree on: numbers to four places,
	/// times as UTC instants, JSON arrays without spaces. The table size is the database's own business.
	private static String cell(String report, Object v) {
		if (v == null) {
			return "-";
		}
		if (report.equals("health.database") && v instanceof Number && ((Number) v).longValue() > 100_000) {
			return "(bytes)";
		}
		if (v instanceof Float || v instanceof Double || v instanceof BigDecimal) {
			double d = ((Number) v).doubleValue();
			return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.4f", d);
		}
		if (v instanceof Date) {
			return ((Date) v).toInstant().toString();
		}
		String text = String.valueOf(v);
		// A JSON array as text: MySQL writes a space after each comma.
		return text.startsWith("[\"") ? text.replace("\", \"", "\",\"") : text;
	}

	// ── the fixture ─────────────────────────────────────────────────────────

	private static void seed() throws SQLException {
		Instant weekAgo = NOW.minusSeconds(7 * 86_400L);
		application(AGENT, "agent", "engine0", weekAgo);
		application(TRANSFER, "transfer", "engine1", weekAgo);
		application(DASHBOARD, "blade-dashboard", "AdminServer", weekAgo);

		// Answered, held, routed, assessed and traced; completed after three minutes.
		long s1 = session(1, AGENT, 0x1A01, NOW.minusSeconds(7200), NOW.minusSeconds(7020));
		key(s1, "ani", "8165550101");
		key(s1, "dnis", "8005550100");
		event(AGENT, s1, "callStarted", NOW.minusSeconds(7200), "{\"caller\":\"8165550101\"}");
		event(AGENT, s1, "callerSaid", NOW.minusSeconds(7195),
				"{\"text\":\"I need to reschedule\",\"intent\":\"reschedule\",\"entity\":\"appointment\",\"addressed\":\"alice\",\"caller\":\"8165550101\",\"destination\":\"8005550100\"}");
		event(AGENT, s1, "callRouted", NOW.minusSeconds(7190),
				"{\"destination\":\"sip:agent@10.0.0.5\",\"endpoint\":\"blade1\",\"tier\":\"primary\",\"failovers\":0}");
		event(AGENT, s1, "callResponded", NOW.minusSeconds(7188),
				"{\"destination\":\"sip:agent@10.0.0.5\",\"status\":200,\"responder\":\"blade1\",\"reason\":\"OK\"}");
		event(AGENT, s1, "callAnswered", NOW.minusSeconds(7185), "{}");
		event(AGENT, s1, "callConnected", NOW.minusSeconds(7185), "{}");
		event(AGENT, s1, "callRiskAssessed", NOW.minusSeconds(7150),
				"{\"riskScore\":0.82,\"riskBand\":\"high\",\"triggerSignal\":\"acoustic\",\"suspectStreak\":2,"
						+ "\"contribution\":{\"acoustic\":0.5,\"signaling\":0.1,\"provenance\":0.12,\"behavior\":0.1},"
						+ "\"signal\":{\"acoustic\":0.9,\"signaling\":0.2,\"provenance\":0.3,\"behavior\":0.25}}");
		event(AGENT, s1, "callRiskFlagged", NOW.minusSeconds(7149), "{\"riskScore\":0.82,\"riskBand\":\"high\",\"triggerSignal\":\"acoustic\"}");
		event(AGENT, s1, "callHeld", NOW.minusSeconds(7100), "{}");
		event(AGENT, s1, "callHoldEnded", NOW.minusSeconds(7090), "{\"heldMs\":10000}");
		event(AGENT, s1, "callTraced", NOW.minusSeconds(7021),
				"{\"method\":\"BYE\",\"status\":200,\"peer\":\"10.0.0.5\",\"finding\":\"ended normally\",\"dropped\":false}");
		event(AGENT, s1, "callCompleted", NOW.minusSeconds(7020), "{}");

		// Queued and abandoned yesterday.
		long s2 = session(2, AGENT, 0x1A02, NOW.minusSeconds(86_400), NOW.minusSeconds(86_340));
		key(s2, "ani", "8165550102");
		event(AGENT, s2, "callStarted", NOW.minusSeconds(86_400), "{}");
		event(AGENT, s2, "queueEntered", NOW.minusSeconds(86_399), "{\"queue\":\"support\",\"depth\":3,\"attempt\":1}");
		event(AGENT, s2, "queueAbandoned", NOW.minusSeconds(86_340), "{\"queue\":\"support\",\"waitedMs\":59000,\"reason\":\"caller hung up\"}");
		event(AGENT, s2, "callAbandoned", NOW.minusSeconds(86_340), "{}");

		// Refused downstream: a branch fails, the call is declined, the trace says why.
		long s3 = session(3, TRANSFER, 0x2B03, NOW.minusSeconds(10_800), NOW.minusSeconds(10_790));
		event(TRANSFER, s3, "callStarted", NOW.minusSeconds(10_800), "{}");
		event(TRANSFER, s3, "callRouted", NOW.minusSeconds(10_799), "{\"destination\":\"sip:queue@10.0.0.9\",\"endpoint\":\"blade2\",\"tier\":\"primary\",\"failovers\":1}");
		event(TRANSFER, s3, "branchFailed", NOW.minusSeconds(10_795),
				"{\"destination\":\"sip:queue@10.0.0.9\",\"status\":503,\"reason\":\"Service Unavailable\",\"responder\":\"blade2\",\"retryAfter\":30}");
		event(TRANSFER, s3, "callDeclined", NOW.minusSeconds(10_790), "{\"status\":503,\"reason\":\"Service Unavailable\"}");
		event(TRANSFER, s3, "callTraced", NOW.minusSeconds(10_790),
				"{\"method\":\"INVITE\",\"status\":503,\"peer\":\"10.0.0.9\",\"finding\":\"refused by the queue\",\"fault\":\"downstream\",\"dropped\":true}");

		// Still up.
		long s4 = session(4, AGENT, 0x1A04, NOW.minusSeconds(600), null);
		key(s4, "ani", "8165550104");
		event(AGENT, s4, "callStarted", NOW.minusSeconds(600), "{}");
		event(AGENT, s4, "callAnswered", NOW.minusSeconds(590), "{}");

		// Two days ago: queued, released, an outbound leg, and an agent's disposition.
		long s5 = session(5, TRANSFER, 0x2B05, NOW.minusSeconds(172_800), NOW.minusSeconds(172_500));
		event(TRANSFER, s5, "callStarted", NOW.minusSeconds(172_800), "{}");
		event(TRANSFER, s5, "queueEntered", NOW.minusSeconds(172_799), "{\"queue\":\"billing\",\"depth\":1,\"attempt\":1}");
		event(TRANSFER, s5, "queueReleased", NOW.minusSeconds(172_780), "{\"queue\":\"billing\",\"waitedMs\":19000}");
		event(TRANSFER, s5, "callOriginated", NOW.minusSeconds(172_779), "{\"party\":\"agent-7\",\"status\":200,\"answered\":true}");
		event(TRANSFER, s5, "callAnswered", NOW.minusSeconds(172_770), "{}");
		event(TRANSFER, s5, "agentDisposition", NOW.minusSeconds(172_600),
				"{\"agent\":\"agent-7\",\"outcome\":\"scam\",\"action\":\"block\",\"treatment\":\"tarpit\",\"reasons\":\"pressure\",\"identity\":\"unverified\",\"face\":\"7\",\"blocked\":true}");
		event(TRANSFER, s5, "callCompleted", NOW.minusSeconds(172_500), "{}");

		// The platform's minute samples, every quarter hour for two hours.
		for (int q = 0; q < 8; q++) {
			Instant at = NOW.minusSeconds(7200 - q * 900L);
			event(DASHBOARD, null, "applicationSampled", at,
					"{\"app\":\"agent\",\"node\":\"engine0\",\"cluster\":\"" + CLUSTER + "\",\"seconds\":60,\"invites\":" + (3 + q)
							+ ",\"published\":" + (10 + q) + ",\"failures\":" + (q % 3 == 0 ? 1 : 0) + ",\"activeAppSessions\":" + q
							+ ",\"restarted\":false,\"sipErrors\":0,\"counters\":[{\"name\":\"invites\",\"delta\":" + (3 + q)
							+ "},{\"name\":\"byes\",\"delta\":2}],\"timings\":[{\"name\":\"route\",\"count\":" + (3 + q)
							+ ",\"sumMillis\":" + (40 + q) + "}]}");
			event(DASHBOARD, null, "serverSampled", at,
					"{\"node\":\"engine0\",\"cluster\":\"" + CLUSTER + "\",\"state\":\"RUNNING\",\"seconds\":60,\"received\":" + (100 + q)
							+ ",\"rejected\":" + (q % 4) + ",\"routed\":" + (90 + q) + ",\"routingCycles\":0,\"routingFallbacks\":0"
							+ ",\"undeployedBypasses\":0,\"appSessionsCreated\":" + (3 + q) + ",\"activeAppSessions\":" + q
							+ ",\"activeSipSessions\":" + (2 * q) + ",\"heapUsedPct\":" + (40 + q) + ",\"cpuPct\":" + (5 + q)
							+ ",\"threadsStuck\":0,\"threadsHogging\":" + (q % 2) + ",\"threadsPending\":0,\"metaspaceMb\":" + (500 + 10 * q)
							+ ",\"classesLoaded\":" + (40_000 + q) + ",\"restarted\":false}");
		}

		// Operations: a change, a problem raised and cleared, the equipment's ups and downs.
		event(DASHBOARD, null, "configSaved", NOW.minusSeconds(5400),
				"{\"actor\":\"weblogic\",\"application\":\"agent\",\"file\":\"agent.json\",\"node\":\"AdminServer\",\"scope\":\"domain\",\"change\":\"queue timeout 30 to 45\"}");
		event(DASHBOARD, null, "configPublished", NOW.minusSeconds(5390),
				"{\"actor\":\"weblogic\",\"application\":\"agent\",\"file\":\"agent.json\",\"node\":\"engine0\",\"scope\":\"domain\"}");
		event(DASHBOARD, null, "configPublished", NOW.minusSeconds(5380),
				"{\"actor\":\"weblogic\",\"application\":\"agent\",\"file\":\"agent.json\",\"node\":\"AdminServer\",\"scope\":\"domain\","
						+ "\"failed\":[\"engine1\",\"engine2\"]}");
		event(AGENT, null, "problemRaised", NOW.minusSeconds(4000),
				"{\"key\":\"stuck-thread\",\"summary\":\"a stuck thread on engine0\",\"application\":\"agent\",\"node\":\"engine0\"}");
		event(AGENT, null, "problemCleared", NOW.minusSeconds(3000), "{\"key\":\"stuck-thread\",\"application\":\"agent\",\"node\":\"engine0\"}");
		event(AGENT, null, "endpointDown", NOW.minusSeconds(3600), "{\"endpoint\":\"blade2\",\"node\":\"engine0\",\"reason\":\"no answer to OPTIONS\"}");
		event(AGENT, null, "endpointUp", NOW.minusSeconds(3300), "{\"endpoint\":\"blade2\",\"node\":\"engine0\"}");
		event(AGENT, null, "trunkFailed", NOW.minusSeconds(2400),
				"{\"gateway\":\"flowroute\",\"registrar\":\"sip.flowroute.com\",\"status\":403,\"reason\":\"Forbidden\",\"node\":\"engine0\"}");
		event(AGENT, null, "trunkRegistered", NOW.minusSeconds(2300), "{\"gateway\":\"flowroute\",\"registrar\":\"sip.flowroute.com\",\"node\":\"engine0\"}");
		event(AGENT, null, "remoteDown", NOW.minusSeconds(1800),
				"{\"remote\":\"crs.example\",\"kind\":\"rest\",\"open\":true,\"application\":\"agent\",\"node\":\"engine0\",\"note\":\"timeout\"}");
		event(AGENT, null, "remoteUp", NOW.minusSeconds(1700), "{\"remote\":\"crs.example\",\"kind\":\"rest\",\"application\":\"agent\",\"node\":\"engine0\"}");
		event(AGENT, null, "queueDepth", NOW.minusSeconds(1200), "{\"queue\":\"support\",\"depth\":12,\"low\":2,\"high\":10,\"node\":\"engine0\"}");
	}

	private static void application(long id, String name, String server, Instant created) throws SQLException {
		try (PreparedStatement p = jdbc.prepareStatement("INSERT INTO applications (id, name, version, host, domain, server, created)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
			p.setLong(1, id);
			p.setString(2, name);
			p.setString(3, "3.0.8");
			p.setString(4, "host-" + server);
			p.setString(5, "fixture-domain");
			p.setString(6, server);
			p.setTimestamp(7, utc(created));
			p.executeUpdate();
		}
	}

	private static long session(int n, long application, long vorpalId, Instant created, Instant destroyed) throws SQLException {
		long id = BASE + 100 + n;
		try (PreparedStatement p = jdbc.prepareStatement("INSERT INTO sessions (id, application_id, cluster_name, vorpal_id,"
				+ " created, destroyed) VALUES (?, ?, ?, ?, ?, ?)")) {
			p.setLong(1, id);
			p.setLong(2, application);
			p.setString(3, CLUSTER);
			p.setLong(4, vorpalId);
			p.setTimestamp(5, utc(created));
			p.setTimestamp(6, destroyed == null ? null : utc(destroyed));
			p.executeUpdate();
		}
		return id;
	}

	private static void key(long session, String name, String value) throws SQLException {
		try (PreparedStatement p = jdbc.prepareStatement("INSERT INTO session_keys (session_id, name, value) VALUES (?, ?, ?)")) {
			p.setLong(1, session);
			p.setString(2, name);
			p.setString(3, value);
			p.executeUpdate();
		}
	}

	private static void event(long application, Long session, String type, Instant created, String payload) throws SQLException {
		long id = nextEvent++;
		try (PreparedStatement p = jdbc.prepareStatement("INSERT INTO events (id, application_id, session_id, type, event_uid,"
				+ " created, payload) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
			p.setLong(1, id);
			p.setLong(2, application);
			if (session == null) {
				p.setNull(3, java.sql.Types.BIGINT);
			} else {
				p.setLong(3, session);
			}
			p.setString(4, type);
			p.setString(5, UUID.nameUUIDFromBytes(Long.toString(id).getBytes(StandardCharsets.UTF_8)).toString());
			p.setTimestamp(6, utc(created));
			p.setString(7, payload);
			p.executeUpdate();
		}
	}

	/// The UTC wall-clock time the analytics service writes, whatever zone
	/// this JVM runs in.
	private static Timestamp utc(Instant at) {
		return Timestamp.valueOf(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
	}

	private static void removeFixture() throws SQLException {
		String apps = "(" + AGENT + ", " + TRANSFER + ", " + DASHBOARD + ")";
		try (Statement s = jdbc.createStatement()) {
			s.executeUpdate("DELETE FROM events WHERE application_id IN " + apps);
			s.executeUpdate("DELETE FROM session_keys WHERE session_id IN (SELECT id FROM sessions WHERE application_id IN " + apps + ")");
			s.executeUpdate("DELETE FROM sessions WHERE application_id IN " + apps);
			s.executeUpdate("DELETE FROM applications WHERE id IN " + apps);
		}
	}
}
