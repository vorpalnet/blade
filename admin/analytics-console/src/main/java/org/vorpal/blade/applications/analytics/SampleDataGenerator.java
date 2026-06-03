package org.vorpal.blade.applications.analytics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;

/// Generates synthetic analytics data for the BLADE analytics schema
/// (`applications` / `sessions` / `session_keys` / `event_types` / `events` /
/// `attribute_names` / `attributes`), modelled on the `transfer` service.
///
/// Every "call" becomes a closed `session` row (so `open_key` is NULL and the
/// open-session unique guard never trips) carrying the `(cluster_name,
/// vorpal_id)` correlator, plus a time-ordered stream of events. Calls are
/// randomized: some are abandoned (ring, no answer), most are answered, and a
/// configurable fraction transfer one or more times.
///
/// This is a dev/test tool. It writes directly to the DB with explicit
/// historical timestamps (the live JMS pipeline can't backdate `created`),
/// through the `jdbc/BladeAnalytics` data source.
final class SampleDataGenerator {

	private static final String DATA_SOURCE_JNDI = "jdbc/BladeAnalytics";

	private SampleDataGenerator() {
	}

	/// Parsed + defaulted generation parameters.
	static final class Params {
		long startMs;          // earliest call-start
		long endMs;            // latest call-start
		int callCount = 500;
		String clusterName = "cluster1";
		String appName = "transfer";
		String appVersion = "2.9.6";
		String tenant;         // customer code → application.tenant; blank/null = NULL (single-tenant)
		int servers = 2;       // number of engine instances (application rows)

		double abandonProbability = 0.10;  // ring, never answered
		double transferProbability = 0.35; // of answered calls, fraction that transfer ≥1×
		int maxTransfers = 3;
		double transferFailProbability = 0.10; // a transfer attempt that fails

		int minDurationSec = 20;
		int maxDurationSec = 1800;
	}

	/// Build Params from the request JSON, applying defaults for any missing
	/// field. Dates are ISO `yyyy-MM-dd` (interpreted as start-of-day UTC) or an
	/// epoch-millis number.
	static Params parse(JsonNode j) {
		Params p = new Params();
		p.clusterName = text(j, "clusterName", p.clusterName);
		p.appName = text(j, "appName", p.appName);
		p.appVersion = text(j, "appVersion", p.appVersion);
		p.tenant = text(j, "tenant", "");
		p.callCount = (int) longVal(j, "callCount", p.callCount);
		p.servers = Math.max(1, (int) longVal(j, "servers", p.servers));

		p.abandonProbability = dbl(j, "abandonProbability", p.abandonProbability);
		p.transferProbability = dbl(j, "transferProbability", p.transferProbability);
		p.maxTransfers = Math.max(1, (int) longVal(j, "maxTransfers", p.maxTransfers));
		p.transferFailProbability = dbl(j, "transferFailProbability", p.transferFailProbability);
		p.minDurationSec = (int) longVal(j, "minDurationSec", p.minDurationSec);
		p.maxDurationSec = (int) longVal(j, "maxDurationSec", p.maxDurationSec);
		if (p.maxDurationSec < p.minDurationSec) {
			p.maxDurationSec = p.minDurationSec;
		}

		long now = nowMs();
		p.startMs = dateMs(j, "startDate", now - 7L * 86_400_000L);
		p.endMs = dateMs(j, "endDate", now);
		if (p.endMs < p.startMs) {
			long t = p.endMs;
			p.endMs = p.startMs;
			p.startMs = t;
		}

		return p;
	}

	/// Result counts, returned to the caller for JSON rendering.
	static final class Result {
		final Map<String, Object> counts = new LinkedHashMap<>();
	}

	/// Run the generation. Throws on connection/SQL failure (the resource maps
	/// it to a 500 with a clear message).
	static Result generate(Params p) throws Exception {
		if (p.callCount <= 0) {
			throw new IllegalArgumentException("callCount must be > 0");
		}
		long t0 = nowMs();
		Random rnd = new Random();

		try (Connection conn = open()) {
			conn.setAutoCommit(false);
			try {
				Result r = run(conn, p, rnd);
				conn.commit();
				r.counts.put("elapsedMs", nowMs() - t0);
				return r;
			} catch (Exception e) {
				safeRollback(conn);
				throw e;
			}
		}
	}

	private static Result run(Connection conn, Params p, Random rnd) throws SQLException {
		Result r = new Result();
		Map<String, Short> eventTypes = new HashMap<>();
		Map<String, Short> attrNames = new HashMap<>();

		// 1) application instances (engine1..engineN)
		long[] appIds = new long[p.servers];
		long appCreated = p.startMs - 3_600_000L; // an hour before the window
		for (int i = 0; i < p.servers; i++) {
			long id = positiveLong(rnd);
			String server = "engine" + (i + 1);
			String host = server + "." + p.clusterName + ".vorpal.net";
			insertApplication(conn, id, p.appName, p.appVersion, host, p.clusterName, server, p.tenant, appCreated);
			appIds[i] = id;
		}

		long sessions = 0, events = 0, attributes = 0, sessionKeys = 0;
		int commitEvery = 200;

		for (int c = 0; c < p.callCount; c++) {
			long appId = appIds[rnd.nextInt(appIds.length)];
			long startMs = p.startMs + (long) (rnd.nextDouble() * Math.max(1, (p.endMs - p.startMs)));
			long vorpalId = rnd.nextInt(Integer.MAX_VALUE); // 31-bit, fits the 8-hex space

			boolean abandoned = rnd.nextDouble() < p.abandonProbability;
			int ringSec = 1 + rnd.nextInt(8);
			int durationSec = abandoned
					? (3 + rnd.nextInt(28))                                   // gave up while ringing
					: (p.minDurationSec + rnd.nextInt(Math.max(1, p.maxDurationSec - p.minDurationSec + 1)));
			long endMs = startMs + durationSec * 1000L;

			long sessionId = insertSession(conn, appId, p.clusterName, vorpalId, startMs, endMs);
			sessions++;

			// session selectors (caller / callee)
			String caller = randomSipUser(rnd);
			String callee = randomSipUser(rnd);
			insertSessionKey(conn, sessionId, "caller", caller);
			insertSessionKey(conn, sessionId, "callee", callee);
			sessionKeys += 2;

			// build the time-ordered event list
			List<Ev> evs = new ArrayList<>();
			evs.add(new Ev("sessionStart", startMs));
			evs.add(new Ev("ringing", startMs + 400));

			if (abandoned) {
				evs.add(new Ev("abandoned", endMs - 200));
			} else {
				long answeredMs = startMs + ringSec * 1000L;
				if (answeredMs >= endMs) {
					answeredMs = startMs + Math.min(1000L, durationSec * 1000L / 2);
				}
				Ev answered = new Ev("answered", answeredMs);
				answered.attrs.put("agent", "agent" + (100 + rnd.nextInt(900)));
				evs.add(answered);

				int transfers = (rnd.nextDouble() < p.transferProbability) ? (1 + rnd.nextInt(p.maxTransfers)) : 0;
				long span = Math.max(1, endMs - answeredMs);
				for (int k = 1; k <= transfers; k++) {
					// spread transfer cycles across the talk time
					long base = answeredMs + (long) (span * (k / (double) (transfers + 1)));
					String target = randomSipUser(rnd);
					boolean blind = rnd.nextBoolean();

					Ev refer = new Ev("referReceived", base);
					refer.attrs.put("transferTarget", target);
					refer.attrs.put("transferType", blind ? "blind" : "attended");
					evs.add(refer);

					evs.add(new Ev("transferInitiated", base + 300));

					boolean failed = rnd.nextDouble() < p.transferFailProbability;
					Ev outcome = new Ev(failed ? "transferFailed" : "transferAnswered", base + 1500);
					outcome.attrs.put("transferTarget", target);
					evs.add(outcome);
				}
				evs.add(new Ev("sessionStop", endMs));
			}
			if (abandoned) {
				evs.add(new Ev("sessionStop", endMs));
			}

			for (Ev ev : evs) {
				short typeId = lookupId(conn, eventTypes, "event_types", ev.name);
				long eventId = insertEvent(conn, appId, sessionId, typeId, ev.when);
				events++;
				for (Map.Entry<String, String> a : ev.attrs.entrySet()) {
					short nameId = lookupId(conn, attrNames, "attribute_names", a.getKey());
					insertAttribute(conn, eventId, nameId, a.getValue());
					attributes++;
				}
			}

			if ((c + 1) % commitEvery == 0) {
				conn.commit();
			}
		}

		r.counts.put("applications", (long) p.servers);
		r.counts.put("sessions", sessions);
		r.counts.put("sessionKeys", sessionKeys);
		r.counts.put("events", events);
		r.counts.put("attributes", attributes);
		r.counts.put("eventTypes", (long) eventTypes.size());
		r.counts.put("attributeNames", (long) attrNames.size());
		return r;
	}

	// ─── a pending event + its attributes ──────────────────────────────────
	private static final class Ev {
		final String name;
		final long when;
		final Map<String, String> attrs = new LinkedHashMap<>();

		Ev(String name, long when) {
			this.name = name;
			this.when = when;
		}
	}

	// ─── connection ────────────────────────────────────────────────────────
	private static Connection open() throws SQLException, NamingException {
		DataSource ds;
		try {
			ds = (DataSource) new InitialContext().lookup(DATA_SOURCE_JNDI);
		} catch (NamingException ne) {
			throw new NamingException("Data source " + DATA_SOURCE_JNDI + " is not bound on this server.");
		}
		return ds.getConnection();
	}

	// ─── inserts ───────────────────────────────────────────────────────────
	private static void insertApplication(Connection conn, long id, String name, String version,
			String host, String domain, String server, String tenant, long createdMs) throws SQLException {
		String sql = "INSERT INTO applications(id, name, version, host, domain, server, tenant, created, destroyed) "
				+ "VALUES (?,?,?,?,?,?,?,?,NULL)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, id);
			ps.setString(2, name);
			ps.setString(3, version);
			ps.setString(4, host);
			ps.setString(5, domain);
			ps.setString(6, server);
			if (tenant == null || tenant.isBlank()) {
				ps.setNull(7, java.sql.Types.VARCHAR);
			} else {
				ps.setString(7, tenant);
			}
			ps.setTimestamp(8, new Timestamp(createdMs));
			ps.executeUpdate();
		}
	}

	private static long insertSession(Connection conn, long appId, String clusterName, long vorpalId,
			long createdMs, long destroyedMs) throws SQLException {
		String sql = "INSERT INTO sessions(application_id, cluster_name, vorpal_id, created, destroyed) "
				+ "VALUES (?,?,?,?,?)";
		try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			ps.setLong(1, appId);
			ps.setString(2, clusterName);
			ps.setLong(3, vorpalId);
			ps.setTimestamp(4, new Timestamp(createdMs));
			ps.setTimestamp(5, new Timestamp(destroyedMs));
			ps.executeUpdate();
			return generatedKey(ps);
		}
	}

	private static void insertSessionKey(Connection conn, long sessionId, String name, String value)
			throws SQLException {
		String sql = "INSERT INTO session_keys(session_id, name, value) VALUES (?,?,?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, sessionId);
			ps.setString(2, name);
			ps.setString(3, value);
			ps.executeUpdate();
		}
	}

	private static long insertEvent(Connection conn, long appId, long sessionId, short eventTypeId, long whenMs)
			throws SQLException {
		String sql = "INSERT INTO events(application_id, session_id, event_type_id, created) VALUES (?,?,?,?)";
		try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
			ps.setLong(1, appId);
			ps.setLong(2, sessionId);
			ps.setShort(3, eventTypeId);
			ps.setTimestamp(4, new Timestamp(whenMs));
			ps.executeUpdate();
			return generatedKey(ps);
		}
	}

	private static void insertAttribute(Connection conn, long eventId, short attrNameId, String value)
			throws SQLException {
		String sql = "INSERT INTO attributes(event_id, attribute_name_id, value) VALUES (?,?,?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, eventId);
			ps.setShort(2, attrNameId);
			ps.setString(3, value);
			ps.executeUpdate();
		}
	}

	/// SELECT-first, INSERT-on-miss for the normalized lookup tables
	/// (`event_type`, `attribute_name`), cached per run.
	private static short lookupId(Connection conn, Map<String, Short> cache, String table, String name)
			throws SQLException {
		Short cached = cache.get(name);
		if (cached != null) {
			return cached;
		}
		try (PreparedStatement sel = conn.prepareStatement("SELECT id FROM " + table + " WHERE name = ?")) {
			sel.setString(1, name);
			try (ResultSet rs = sel.executeQuery()) {
				if (rs.next()) {
					short id = rs.getShort(1);
					cache.put(name, id);
					return id;
				}
			}
		}
		try (PreparedStatement ins = conn.prepareStatement(
				"INSERT INTO " + table + "(name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
			ins.setString(1, name);
			ins.executeUpdate();
			short id = (short) generatedKey(ins);
			cache.put(name, id);
			return id;
		}
	}

	private static long generatedKey(PreparedStatement ps) throws SQLException {
		try (ResultSet keys = ps.getGeneratedKeys()) {
			if (keys.next()) {
				return keys.getLong(1);
			}
		}
		throw new SQLException("no generated key returned");
	}

	private static void safeRollback(Connection conn) {
		try {
			conn.rollback();
		} catch (SQLException ignore) {
			// nothing useful to do; the original exception propagates
		}
	}

	// ─── value helpers ───────────────────────────────────────────────────────
	private static String randomSipUser(Random rnd) {
		long n = 2_000_000_000L + (long) (rnd.nextDouble() * 7_999_999_999L); // 10-digit
		return "sip:+1" + n + "@pstn.example.net";
	}

	private static long positiveLong(Random rnd) {
		return rnd.nextLong() & Long.MAX_VALUE;
	}

	// ─── JSON field readers ──────────────────────────────────────────────────
	private static String text(JsonNode j, String f, String dflt) {
		JsonNode n = j.get(f);
		return (n == null || n.isNull()) ? dflt : n.asText();
	}

	private static long longVal(JsonNode j, String f, long dflt) {
		JsonNode n = j.get(f);
		return (n == null || n.isNull()) ? dflt : n.asLong(dflt);
	}

	private static double dbl(JsonNode j, String f, double dflt) {
		JsonNode n = j.get(f);
		return (n == null || n.isNull()) ? dflt : n.asDouble(dflt);
	}

	/// Accept an ISO `yyyy-MM-dd` string (start-of-day UTC) or epoch-millis.
	private static long dateMs(JsonNode j, String f, long dflt) {
		JsonNode n = j.get(f);
		if (n == null || n.isNull()) {
			return dflt;
		}
		if (n.isNumber()) {
			return n.asLong();
		}
		String s = n.asText().trim();
		if (s.isEmpty()) {
			return dflt;
		}
		try {
			return java.time.LocalDate.parse(s)
					.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
		} catch (RuntimeException e) {
			return dflt;
		}
	}

	private static long nowMs() {
		return System.currentTimeMillis();
	}
}
