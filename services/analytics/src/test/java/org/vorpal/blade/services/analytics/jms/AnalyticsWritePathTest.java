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
import java.util.List;
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

	private EntityManagerFactory factory;
	private AnalyticsEventListener listener;
	private Connection keepAlive;

	@BeforeEach
	void setUp() throws Exception {
		// HSQLDB drops an in-memory database when the last connection closes.
		// Holding one open for the test keeps the schema alive between the
		// listener's own short-lived connections.
		keepAlive = DriverManager.getConnection("jdbc:hsqldb:mem:blade-analytics", "sa", "");
		runSchema(keepAlive);

		factory = Persistence.createEntityManagerFactory("BladeAnalyticsTest");
		listener = new AnalyticsEventListener(factory);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (factory != null && factory.isOpen()) {
			factory.close();
		}
		try (Statement statement = keepAlive.createStatement()) {
			statement.execute("SHUTDOWN");
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

	private void runSchema(Connection connection) throws Exception {
		String sql;
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("hsqldb-schema.sql")) {
			assertNotNull(in, "hsqldb-schema.sql is missing from the test resources");
			sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		try (Statement statement = connection.createStatement()) {
			for (String each : sql.split(";")) {
				String trimmed = stripComments(each).trim();
				if (!trimmed.isEmpty()) {
					statement.execute(trimmed);
				}
			}
		}
	}

	private static String stripComments(String sql) {
		StringBuilder out = new StringBuilder(sql.length());
		for (String line : sql.split("\\r?\\n")) {
			if (!line.trim().startsWith("--")) {
				out.append(line).append('\n');
			}
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
