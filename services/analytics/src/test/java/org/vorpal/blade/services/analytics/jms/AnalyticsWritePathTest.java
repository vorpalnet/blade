package org.vorpal.blade.services.analytics.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CloudEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Exercises the analytics write path against a real database.
///
/// **This is the test whose absence let an insert-ordering bug reach a live
/// deploy.** It compiled, it passed every other test, and it failed on the
/// first phone call with `ORA-02291` — a foreign key violation, because these
/// entities declare no JPA relationships and the provider is therefore free to
/// write an event before the session it points at. Nothing without a database
/// could have seen that.
///
/// HSQLDB in memory, with the same four tables and the same foreign keys the
/// shipped schemas define (`src/test/resources/hsqldb-schema.sql`). The
/// dialect-specific parts neither database shares — MySQL's generated
/// `open_key`, both JSON functional indexes — are left out; they are storage
/// and query concerns, and the foreign keys are what this is about.
class AnalyticsWritePathTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String CLUSTER = "test-cluster";

	/// Point this at a real SQL Server and the whole suite runs against it
	/// instead of HSQLDB — same assertions, same handler, a different engine
	/// and a different JPA platform.
	///
	/// **Opt-in, because CI must run offline.** Unset, everything below uses
	/// the in-memory database and nothing reaches the network. Set, it proves
	/// the thing HSQLDB structurally cannot: that EclipseLink's SQL Server
	/// platform writes these entities correctly. That exact question went
	/// unasked on Oracle, where the provider quietly substituted a sequence
	/// no schema created and the sink had never written a row.
	///
	///     BLADE_MSSQL_URL=jdbc:sqlserver://host:1433;databaseName=vorpal;encrypt=false
	///     BLADE_MSSQL_USER=sa
	///     BLADE_MSSQL_PASSWORD=...
	private static final String MSSQL_URL = System.getenv("BLADE_MSSQL_URL");

	private EntityManagerFactory factory;
	private AnalyticsEventListener listener;
	private Connection keepAlive;

	@BeforeEach
	void setUp() throws Exception {
		Map<String, Object> properties = new HashMap<>();

		if (MSSQL_URL != null && !MSSQL_URL.isEmpty()) {
			keepAlive = DriverManager.getConnection(MSSQL_URL,
					System.getenv("BLADE_MSSQL_USER"), System.getenv("BLADE_MSSQL_PASSWORD"));
			runSchema(keepAlive, "mssql-schema.sql");
			properties.put("javax.persistence.jdbc.driver",
					"com.microsoft.sqlserver.jdbc.SQLServerDriver");
			properties.put("javax.persistence.jdbc.url", MSSQL_URL);
			properties.put("javax.persistence.jdbc.user", System.getenv("BLADE_MSSQL_USER"));
			properties.put("javax.persistence.jdbc.password", System.getenv("BLADE_MSSQL_PASSWORD"));
		} else {
			// HSQLDB drops an in-memory database when the last connection
			// closes. Holding one open keeps the schema alive between the
			// listener's own short-lived connections.
			keepAlive = DriverManager.getConnection("jdbc:hsqldb:mem:blade-analytics", "sa", "");
			runSchema(keepAlive, "hsqldb-schema.sql");
		}

		factory = Persistence.createEntityManagerFactory("BladeAnalyticsTest", properties);
		listener = new AnalyticsEventListener(factory);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (factory != null && factory.isOpen()) {
			factory.close();
		}
		if (MSSQL_URL == null || MSSQL_URL.isEmpty()) {
			try (Statement statement = keepAlive.createStatement()) {
				statement.execute("SHUTDOWN");
			}
		}
		keepAlive.close();
	}

	@Test
	@DisplayName("an event writes its application and session before itself")
	void parentsAreWrittenBeforeChildren() throws Exception {
		// The ORA-02291 case: nothing has been seen before, so all three rows
		// are new and the order they are inserted in is the whole question.
		listener.handle(Collections.singletonList(callEvent(UUID.randomUUID().toString(), 0x1234ABCDL)));

		assertEquals(1, count("applications"));
		assertEquals(1, count("sessions"));
		assertEquals(1, count("events"));
		assertEquals(1, count("events e JOIN sessions s ON s.id = e.session_id"),
				"the event must point at the session that was written for it");
	}

	@Test
	@DisplayName("the same event applied twice writes one row")
	void redeliveryIsANoOp() throws Exception {
		// The property the whole key design rests on. A durable subscription
		// replays on every rolling restart, and a batch that fails halfway is
		// redelivered whole — including the events that already succeeded.
		String uid = UUID.randomUUID().toString();
		CloudEvent event = callEvent(uid, 0x22222222L);

		listener.handle(Collections.singletonList(event));
		listener.handle(Collections.singletonList(callEvent(uid, 0x22222222L)));

		assertEquals(1, count("events"), "a replayed event must collide with its own row");
		assertEquals(1, count("sessions"));
		assertEquals(1, count("applications"));
	}

	@Test
	@DisplayName("a whole batch re-applied after a partial failure lands exactly once")
	void reapplyingABatchIsSafe() throws Exception {
		List<CloudEvent> batch = Arrays.asList(
				callEvent(UUID.randomUUID().toString(), 0x33330001L),
				callEvent(UUID.randomUUID().toString(), 0x33330002L),
				callEvent(UUID.randomUUID().toString(), 0x33330003L));

		listener.handle(batch);
		listener.handle(batch);

		assertEquals(3, count("events"));
		assertEquals(3, count("sessions"));
	}

	@Test
	@DisplayName("an event with no birth instant adopts the correlator's open session")
	void nullStartedAtAdoptsTheOpenSession() throws Exception {
		long vorpalId = 0x44444444L;

		// First a normal event, which creates the session.
		listener.handle(Collections.singletonList(callEvent(UUID.randomUUID().toString(), vorpalId)));
		assertEquals(1, count("sessions"));
		long sessionId = single("SELECT id FROM sessions");

		// Then one carrying the same correlator but no startedAt — the legacy
		// shape. It must attach to the session that already exists rather than
		// invent a second one, and it must do that WITHOUT re-deriving a key
		// from the stored timestamp.
		CloudEvent orphan = callEvent(UUID.randomUUID().toString(), vorpalId);
		((ObjectNode) orphan.getData()).remove("startedAt");
		listener.handle(Collections.singletonList(orphan));

		assertEquals(1, count("sessions"), "a missing birth instant must not create a second session");
		assertEquals(2, count("events"));
		assertEquals(2, count("events WHERE session_id = " + sessionId),
				"both events belong to the one session");
	}

	@Test
	@DisplayName("the risk payload is stored as queryable JSON")
	void payloadHoldsTheAttributes() throws Exception {
		listener.handle(Collections.singletonList(callEvent(UUID.randomUUID().toString(), 0x55555555L)));

		String payload = text("SELECT payload FROM events");
		assertNotNull(payload);
		assertEquals("0.661", MAPPER.readTree(payload).path("riskScore").asText(),
				"the attribute the demo query reads must survive the write");
	}

	// ─────────────────────────────────────────────────────────────── fixtures

	/// A `callRiskAssessed` on the wire, shaped the way the framework's mapper
	/// publishes one.
	private CloudEvent callEvent(String uid, long vorpalId) {
		long birth = 1_787_780_167_411L;

		ObjectNode data = MAPPER.createObjectNode();
		data.put("appName", "conference");
		data.put("domain", CLUSTER);
		data.put("server", "engine1");
		data.put("appStartedAt", iso(birth - 3_600_000L));
		data.put("vorpalId", String.format("%08X", vorpalId));
		// The call's BIRTH instant — identity. Distinct from occurredAt below,
		// which is when this particular fact happened.
		data.put("startedAt", iso(birth));
		data.put("occurredAt", iso(birth + 12_000L));
		data.put("eventName", "callRiskAssessed");

		ArrayNode attributes = data.putArray("attributes");
		attributes.addObject().put("name", "riskScore").put("value", "0.661");
		attributes.addObject().put("name", "riskBand").put("value", "WATCH");
		attributes.addObject().put("name", "signal.acoustic").put("value", "0.985");

		CloudEvent event = new CloudEvent();
		event.setType(BladeEventTypes.CALL_EVENT);
		event.setId(uid);
		event.setSource("//blade/conference/test");
		event.setData(data);
		return event;
	}

	private static String iso(long millis) {
		return java.time.Instant.ofEpochMilli(millis).toString();
	}

	// ──────────────────────────────────────────────────────────────── queries

	private void runSchema(Connection connection, String resource) throws Exception {
		String sql;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
			assertNotNull(in, resource + " is missing from the test resources");
			sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		// Strip comments FIRST, then split.
		//
		// The other order splits a comment containing a semicolon in half and
		// executes its tail as SQL, which fails somewhere in the middle of an
		// English sentence ("Incorrect syntax near the keyword 'is'") and reads
		// like a schema problem rather than a parsing one.
		try (Statement statement = connection.createStatement()) {
			for (String each : stripComments(sql).split(";")) {
				String trimmed = each.trim();
				if (!trimmed.isEmpty()) {
					statement.execute(trimmed);
				}
			}
		}
	}

	/// Remove `--` comments, INLINE ones included.
	///
	/// Whole-line stripping is not enough: the shipped schemas annotate columns
	/// on the same line, and one of those annotations contains a semicolon
	/// ("multi-tenant RLS; NULL = single-tenant"). Left in, it splits a
	/// CREATE TABLE through the middle of its column list.
	///
	/// This would also mangle a `--` inside a string literal. None of the three
	/// schemas contains one, and a test helper that assumes its own inputs is a
	/// fair trade against writing a SQL lexer here.
	private static String stripComments(String sql) {
		StringBuilder out = new StringBuilder(sql.length());
		for (String line : sql.split("\\r?\\n")) {
			out.append(line.replaceAll("--.*$", "")).append('\n');
		}
		return out.toString();
	}

	private long count(String fromClause) throws Exception {
		return single("SELECT COUNT(*) FROM " + fromClause);
	}

	private long single(String sql) throws Exception {
		try (Statement statement = keepAlive.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : -1L;
		}
	}

	private String text(String sql) throws Exception {
		try (Statement statement = keepAlive.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
			return rs.next() ? rs.getString(1) : null;
		}
	}
}
