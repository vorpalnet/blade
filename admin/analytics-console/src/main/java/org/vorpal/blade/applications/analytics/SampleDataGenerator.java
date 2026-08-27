package org.vorpal.blade.applications.analytics;

import java.sql.Connection;
import java.sql.PreparedStatement;
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

import org.vorpal.blade.framework.v3.analytics.NaturalKey;

import com.fasterxml.jackson.databind.JsonNode;

/// Generates synthetic analytics data for the BLADE analytics schema
/// (`applications` / `sessions` / `session_keys` / `events`), modelled on the
/// `transfer` service.
///
/// **Every key here is computed exactly as the live writer computes it**, via
/// [NaturalKey]. That is not tidiness: this tool writes over raw JDBC, beside a
/// service that writes through JPA, and it is the one place where a second
/// opinion about where a row belongs would go unnoticed. Sample rows land where
/// recorded rows would, so a query written against generated data behaves the
/// same against real data — which is the entire point of generating any.
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
		// 1) application instances (engine1..engineN)
		long[] appIds = new long[p.servers];
		long appCreated = p.startMs - 3_600_000L; // an hour before the window
		for (int i = 0; i < p.servers; i++) {
			String server = "engine" + (i + 1);
			String host = server + "." + p.clusterName + ".vorpal.net";
			// The same key the live writer would compute for this instance. A
			// random id here would put sample rows somewhere the service could
			// never reach, so a generated call and a real one would not share
			// an application even when they describe the same instance.
			long id = NaturalKey.idFor(p.appName, p.clusterName, server, new java.util.Date(appCreated));
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
				insertEvent(conn, appId, sessionId, ev.name, ev.when, payloadOf(ev.attrs));
				events++;
				attributes += ev.attrs.size();
			}

			if ((c + 1) % commitEvery == 0) {
				conn.commit();
			}
		}

		r.counts.put("applications", (long) p.servers);
		r.counts.put("sessions", sessions);
		r.counts.put("sessionKeys", sessionKeys);
		r.counts.put("events", events);
		// Attributes are no longer rows of their own — they are keys inside each
		// event's JSON payload. Still counted, because "how much did this
		// generate" is the question this number answers.
		r.counts.put("attributes", attributes);
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
		long id = NaturalKey.idFor(clusterName, vorpalId, new java.util.Date(createdMs));
		String sql = "INSERT INTO sessions(id, application_id, cluster_name, vorpal_id, created, destroyed) "
				+ "VALUES (?,?,?,?,?,?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, id);
			ps.setLong(2, appId);
			ps.setString(3, clusterName);
			ps.setLong(4, vorpalId);
			ps.setTimestamp(5, new Timestamp(createdMs));
			ps.setTimestamp(6, new Timestamp(destroyedMs));
			ps.executeUpdate();
			return id;
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

	private static void insertEvent(Connection conn, long appId, long sessionId, String type, long whenMs,
			String payload) throws SQLException {
		// Sample rows get a real CloudEvent id and the key derived from it, the
		// same as a live event, so a generated row is indistinguishable from a
		// recorded one to anything reading the table.
		String eventUid = java.util.UUID.randomUUID().toString();
		String sql = "INSERT INTO events(id, application_id, session_id, type, event_uid, created, payload) "
				+ "VALUES (?,?,?,?,?,?,?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, NaturalKey.idFor(eventUid));
			ps.setLong(2, appId);
			ps.setLong(3, sessionId);
			ps.setString(4, type);
			ps.setString(5, eventUid);
			ps.setTimestamp(6, new Timestamp(whenMs));
			ps.setString(7, payload);
			ps.executeUpdate();
		}
	}

	/// An event's attributes as the JSON object the `payload` column holds.
	///
	/// Values are quoted strings, matching what the live writer stores: the
	/// wire carries attribute values as text and this keeps sample data honest
	/// about that, so a query written against generated rows behaves the same
	/// against recorded ones.
	private static String payloadOf(Map<String, String> attrs) {
		if (attrs == null || attrs.isEmpty()) {
			return "{}";
		}
		StringBuilder json = new StringBuilder(64).append('{');
		boolean first = true;
		for (Map.Entry<String, String> a : attrs.entrySet()) {
			if (!first) {
				json.append(',');
			}
			first = false;
			json.append('"').append(escape(a.getKey())).append("\":\"")
					.append(escape(a.getValue())).append('"');
		}
		return json.append('}').toString();
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}


	/// SELECT-first, INSERT-on-miss for the normalized lookup tables


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
