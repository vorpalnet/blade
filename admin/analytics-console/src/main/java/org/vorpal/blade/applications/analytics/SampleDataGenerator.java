package org.vorpal.blade.applications.analytics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.vorpal.blade.framework.v3.analytics.NaturalKey;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;

import com.fasterxml.jackson.databind.JsonNode;

/// Generates synthetic analytics data for the BLADE analytics schema
/// (`applications` / `sessions` / `session_keys` / `events`).
///
/// **Every key here is computed exactly as the live writer computes it**, via
/// [NaturalKey]. That is not tidiness: this tool writes over raw JDBC, beside a
/// service that writes through JPA, and it is the one place where a second
/// opinion about where a row belongs would go unnoticed. Sample rows land where
/// recorded rows would, so a query written against generated data behaves the
/// same against real data — which is the entire point of generating any.
///
/// **The event names come from the framework's closed set, not from strings
/// invented here.** A call becomes `callStarted` → `callAnswered` →
/// `callConnected` → `callCompleted`, or `callAbandoned` (given up while
/// ringing), or `callDeclined` (a failure response); transfers become
/// `transferRequested` → `transferInitiated` → `transferCompleted`/
/// `transferDeclined`. These are the eleven names `InitialInvite`, `Terminate`,
/// `BlindTransfer` and `ReferTransfer` publish, as recorded in
/// [BladeEventTypes] / `BladeEventCatalog`, stored verbatim in `events.type`
/// exactly as the live writer stores `data.eventName`. [#assertFrameworkNames]
/// guards them against a rename: if one stops resolving to a framework type,
/// generation fails loudly rather than seeding data the dashboard cannot match.
///
/// Each call becomes a closed `session` row (so the open-session guard never
/// trips) carrying the `(cluster_name, vorpal_id)` correlator, plus a
/// time-ordered stream of events. Session lifecycle is the `sessions` row's
/// `created`/`destroyed`, never an `events` row. Call starts are weighted onto
/// business hours and weekdays, so the heatmap and calls-per-hour show a
/// call-center shape rather than uniform noise.
///
/// This is a dev/test tool. It writes directly to the DB with explicit
/// historical timestamps (the live JMS pipeline can't backdate `created`),
/// through the `jdbc/BladeAnalytics` data source. Sample runs default their
/// `clusterName` to `sample`, so a run is removable with
/// `DELETE FROM sessions WHERE cluster_name = 'sample'` (and the matching
/// `applications`/`events`).
final class SampleDataGenerator {

	private static final String DATA_SOURCE_JNDI = "jdbc/BladeAnalytics";

	// The framework's closed event set, stored verbatim in events.type. Literals
	// (there are no camelCase constants — BladeEventTypes exposes the dotted wire
	// types and forEventName() as the inverse), guarded by assertFrameworkNames().
	private static final String CALL_STARTED = "callStarted";
	private static final String CALL_ANSWERED = "callAnswered";
	private static final String CALL_CONNECTED = "callConnected";
	private static final String CALL_COMPLETED = "callCompleted";
	private static final String CALL_ABANDONED = "callAbandoned";
	private static final String CALL_DECLINED = "callDeclined";
	private static final String TRANSFER_REQUESTED = "transferRequested";
	private static final String TRANSFER_INITIATED = "transferInitiated";
	private static final String TRANSFER_COMPLETED = "transferCompleted";
	private static final String TRANSFER_DECLINED = "transferDeclined";
	private static final String TRANSFER_ABANDONED = "transferAbandoned";

	private static final String[] FRAMEWORK_NAMES = {
			CALL_STARTED, CALL_ANSWERED, CALL_CONNECTED, CALL_COMPLETED, CALL_ABANDONED, CALL_DECLINED,
			TRANSFER_REQUESTED, TRANSFER_INITIATED, TRANSFER_COMPLETED, TRANSFER_DECLINED, TRANSFER_ABANDONED };

	// Not a framework name — the GryphonRiskTap detector's assessment, which lands
	// on BladeEventTypes.CALL_EVENT with its name in the payload, like any other
	// operator-defined event. Emitted only when riskProbability > 0.
	private static final String CALL_RISK_ASSESSED = "callRiskAssessed";

	/// Per-hour weight (0..23) and per-weekday weight (Mon..Sun): a business-hours
	/// diurnal shape used to place call starts, so the heatmap looks like a call
	/// center. Weights are relative; only their ratios matter.
	private static final double[] HOUR_WEIGHT = {
			.02, .01, .01, .01, .02, .04, .10, .30, .65, .90, 1.0, .95,
			.85, .90, .95, .90, .80, .60, .40, .25, .15, .10, .06, .03 };
	private static final double[] DOW_WEIGHT = { 1.0, 1.0, 1.0, 1.0, .95, .35, .25 };

	private SampleDataGenerator() {
	}

	/// Parsed + defaulted generation parameters.
	static final class Params {
		long startMs;          // earliest call-start
		long endMs;            // latest call-start
		int callCount = 500;
		String clusterName = "sample";   // distinct by default so a run is easy to delete
		String appName = "transfer";
		String appVersion = "2.9.6";
		String tenant;         // customer code → application.tenant; blank/null = NULL (single-tenant)
		int servers = 2;       // number of engine instances (application rows)

		double abandonProbability = 0.10;  // ring, caller gives up (callAbandoned)
		double declineProbability = 0.05;  // failure response (callDeclined)
		double transferProbability = 0.35; // of answered calls, fraction that transfer ≥1×
		int maxTransfers = 3;
		double transferFailProbability = 0.10; // a transfer attempt that is declined
		double riskProbability = 0.0;      // of calls, fraction with a callRiskAssessed event (opt-in)

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
		p.declineProbability = dbl(j, "declineProbability", p.declineProbability);
		p.transferProbability = dbl(j, "transferProbability", p.transferProbability);
		p.maxTransfers = Math.max(1, (int) longVal(j, "maxTransfers", p.maxTransfers));
		p.transferFailProbability = dbl(j, "transferFailProbability", p.transferFailProbability);
		p.riskProbability = dbl(j, "riskProbability", p.riskProbability);
		p.minDurationSec = (int) longVal(j, "minDurationSec", p.minDurationSec);
		p.maxDurationSec = (int) longVal(j, "maxDurationSec", p.maxDurationSec);
		if (p.maxDurationSec < p.minDurationSec) {
			p.maxDurationSec = p.minDurationSec;
		}
		// abandon + decline share the "not answered" budget; clamp so they never
		// exceed all calls.
		if (p.abandonProbability + p.declineProbability > 1.0) {
			double scale = 1.0 / (p.abandonProbability + p.declineProbability);
			p.abandonProbability *= scale;
			p.declineProbability *= scale;
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
		assertFrameworkNames();
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

	/// Fail loudly if a name we emit no longer resolves to a framework CloudEvents
	/// type — i.e. [BladeEventTypes] was renamed and this generator was not. Better
	/// a clear error here than silently seeding data the dashboard cannot match.
	private static void assertFrameworkNames() {
		for (String name : FRAMEWORK_NAMES) {
			if (BladeEventTypes.CALL_EVENT.equals(BladeEventTypes.forEventName(name))) {
				throw new IllegalStateException("Event name '" + name
						+ "' no longer maps to a framework type. BladeEventTypes changed; update SampleDataGenerator.");
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
			// The same key the live writer would compute for this instance, so a
			// generated call and a real one describing the same instance share an
			// application row rather than splitting into two.
			long id = NaturalKey.idFor(p.appName, p.clusterName, server, new java.util.Date(appCreated));
			insertApplication(conn, id, p.appName, p.appVersion, host, p.clusterName, server, p.tenant, appCreated);
			appIds[i] = id;
		}

		long sessions = 0, events = 0, attributes = 0, sessionKeys = 0, riskEvents = 0;
		int commitEvery = 200;

		for (int c = 0; c < p.callCount; c++) {
			long appId = appIds[rnd.nextInt(appIds.length)];
			long startMs = sampleStartMs(rnd, p.startMs, p.endMs);
			long vorpalId = rnd.nextInt(Integer.MAX_VALUE); // 31-bit, fits the 8-hex space

			double roll = rnd.nextDouble();
			boolean abandoned = roll < p.abandonProbability;
			boolean declined = !abandoned && roll < (p.abandonProbability + p.declineProbability);
			boolean answered = !abandoned && !declined;

			int ringSec = 1 + rnd.nextInt(8);
			int durationSec = answered
					? (p.minDurationSec + rnd.nextInt(Math.max(1, p.maxDurationSec - p.minDurationSec + 1)))
					: (3 + rnd.nextInt(28));          // gave up / rejected while ringing
			long endMs = startMs + durationSec * 1000L;

			long sessionId = insertSession(conn, appId, p.clusterName, vorpalId, startMs, endMs);
			sessions++;

			// session selectors (caller / callee)
			String caller = randomSipUser(rnd);
			String callee = randomSipUser(rnd);
			insertSessionKey(conn, sessionId, "caller", caller);
			insertSessionKey(conn, sessionId, "callee", callee);
			sessionKeys += 2;

			// the time-ordered event stream — the framework's dispositions, no
			// synthetic session lifecycle rows (that is the sessions row above).
			List<Ev> evs = new ArrayList<>();
			evs.add(new Ev(CALL_STARTED, startMs));

			if (abandoned) {
				evs.add(new Ev(CALL_ABANDONED, endMs - 200));
			} else if (declined) {
				Ev declinedEv = new Ev(CALL_DECLINED, endMs - 200);
				declinedEv.attrs.put("status", String.valueOf(new int[] { 486, 480, 503, 603 }[rnd.nextInt(4)]));
				evs.add(declinedEv);
			} else {
				long answeredMs = startMs + ringSec * 1000L;
				if (answeredMs >= endMs) {
					answeredMs = startMs + Math.min(1000L, durationSec * 1000L / 2);
				}
				Ev ans = new Ev(CALL_ANSWERED, answeredMs);
				ans.attrs.put("agent", "agent" + (100 + rnd.nextInt(900)));
				evs.add(ans);
				evs.add(new Ev(CALL_CONNECTED, answeredMs + 300));

				int transfers = (rnd.nextDouble() < p.transferProbability) ? (1 + rnd.nextInt(p.maxTransfers)) : 0;
				long span = Math.max(1, endMs - answeredMs);
				for (int k = 1; k <= transfers; k++) {
					long base = answeredMs + (long) (span * (k / (double) (transfers + 1)));
					String target = randomSipUser(rnd);
					boolean blind = rnd.nextBoolean();

					Ev refer = new Ev(TRANSFER_REQUESTED, base);
					refer.attrs.put("transferTarget", target);
					refer.attrs.put("transferType", blind ? "blind" : "attended");
					evs.add(refer);

					evs.add(new Ev(TRANSFER_INITIATED, base + 300));

					boolean failed = rnd.nextDouble() < p.transferFailProbability;
					Ev outcome = new Ev(failed ? TRANSFER_DECLINED : TRANSFER_COMPLETED, base + 1500);
					outcome.attrs.put("transferTarget", target);
					evs.add(outcome);
				}
				evs.add(new Ev(CALL_COMPLETED, endMs));
			}

			// optional risk assessment (opt-in) — shortly after the call starts
			if (p.riskProbability > 0 && rnd.nextDouble() < p.riskProbability) {
				Ev risk = riskEvent(rnd, startMs + 1500);
				evs.add(risk);
				riskEvents++;
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
		r.counts.put("riskEvents", riskEvents);
		// Attributes are keys inside each event's JSON payload, not rows of their
		// own; still counted, because "how much did this generate" is the question
		// this number answers.
		r.counts.put("attributes", attributes);
		return r;
	}

	/// A `callRiskAssessed` event with a plausible band/score, so the fraud panel
	/// has something to render. Bands are weighted toward CLEAR, as real traffic is.
	private static Ev riskEvent(Random rnd, long when) {
		double u = rnd.nextDouble();
		String band;
		double score;
		if (u < 0.80) {
			band = "CLEAR";
			score = rnd.nextDouble() * 0.35;
		} else if (u < 0.95) {
			band = "WATCH";
			score = 0.35 + rnd.nextDouble() * 0.45;
		} else {
			band = "SUSPECT";
			score = 0.80 + rnd.nextDouble() * 0.20;
		}
		Ev risk = new Ev(CALL_RISK_ASSESSED, when);
		risk.attrs.put("riskBand", band);
		risk.attrs.put("riskScore", String.format(java.util.Locale.ROOT, "%.3f", score));
		return risk;
	}

	/// A call-start time weighted onto business hours and weekdays by rejection
	/// sampling against [#HOUR_WEIGHT] × [#DOW_WEIGHT]; falls back to a uniform
	/// pick if the window is so short nothing is accepted in a bounded number of
	/// tries.
	private static long sampleStartMs(Random rnd, long startMs, long endMs) {
		long span = Math.max(1, endMs - startMs);
		for (int tries = 0; tries < 64; tries++) {
			long t = startMs + (long) (rnd.nextDouble() * span);
			ZonedDateTime z = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC);
			double w = HOUR_WEIGHT[z.getHour()] * DOW_WEIGHT[z.getDayOfWeek().getValue() - 1];
			if (rnd.nextDouble() <= w) {
				return t;
			}
		}
		return startMs + (long) (rnd.nextDouble() * span);
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
