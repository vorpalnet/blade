package org.vorpal.blade.applications.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.sql.DataSource;

/// Read/write access to the BLADE call catalog for the console.
///
/// Reads the caller's history for the screen-pop and writes the label an agent
/// report produces. Both are best-effort: a missing data source, an unindexed
/// caller, or any SQL error yields empty history and a swallowed (logged) write,
/// never a failed call or a broken pop. All caller values are bound as
/// parameters, never concatenated.
///
/// Two schemas, side by side in the one data source:
///
/// - the analytics sink's `sessions` / `session_keys` (see
///   `services/analytics/sql`): one session per call from every BLADE app with
///   analytics on, keyed by whatever session selectors the app configured. The
///   agent app's sample configures `ani` and `dnis`, so this is where "called
///   N times before" and the recent list come from: every call, recorded or not;
/// - the call catalog's `BLADE_CONVERSATION(FROM_NUMBER, TO_NUMBER, EPOCH_UTC,
///   DURATION_MS, CONVERSATION, ...)`, `BLADE_LABEL` and `BLADE_CONVERSATION_ATTR`:
///   recorded conversations only, but the ones that carry labels and topics.
///
/// History is counted from the sessions when that schema is present, else from
/// the catalog table; labels and topics always come from the catalog.
public final class Catalog {

	private static final Logger LOG = Logger.getLogger(Catalog.class.getName());
	private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

	private final String dataSource;
	private final String conversationTable;
	private final String spamTable;

	public Catalog(String dataSource, String conversationTable, String spamTable) {
		this.dataSource = dataSource;
		this.conversationTable = (conversationTable == null || conversationTable.isBlank())
				? "BLADE_CONVERSATION" : conversationTable;
		this.spamTable = (spamTable == null || spamTable.isBlank()) ? "spam_numbers" : spamTable;
	}

	/// True when a data source is configured; otherwise every read is empty and
	/// every write a no-op.
	public boolean enabled() {
		return dataSource != null && !dataSource.isBlank();
	}

	/// The caller's history: call count, the most recent `limit` calls, and any
	/// labels attached to this caller's past conversations.
	public CallerHistory history(String ani, int limit) {
		if (!enabled() || ani == null || ani.isBlank()) {
			return CallerHistory.EMPTY;
		}
		try {
			DataSource ds = (DataSource) new InitialContext().lookup(dataSource);
			try (Connection c = ds.getConnection()) {
				int count;
				List<CallerHistory.Call> recent;
				try {
					count = sessionCount(c, ani);
					recent = sessionRecent(c, ani, Math.max(1, limit));
				} catch (Exception noAnalytics) {
					// No analytics schema in this data source: count recorded
					// conversations instead.
					AgentConsoleRegistry.log("agent: analytics sessions unavailable, using the catalog: " + noAnalytics);
					count = count(c, ani);
					recent = recent(c, ani, Math.max(1, limit));
				}
				List<String> labels = priorLabels(c, ani);
				List<String> topics = topics(c, ani, Math.max(1, limit));
				return new CallerHistory(count, recent, labels, topics);
			}
		} catch (Exception e) {
			AgentConsoleRegistry.log("agent: caller history unavailable for " + ani + ": " + e);
			return CallerHistory.EMPTY;
		}
	}

	/// A session counts as a prior call once it is closed, or once it has been
	/// open this long: the call ringing right now has an open row seconds old,
	/// so the count stays "before", while a row a redeploy or a crash left open
	/// forever (its stop never published) still counts as the call it was.
	private static final String PRIOR = " AND (s.destroyed IS NOT NULL OR s.created < SYSTIMESTAMP - INTERVAL '2' MINUTE)";

	/// Prior calls from this number, across every app that publishes a session
	/// key named `ani`.
	private int sessionCount(Connection c, String ani) throws Exception {
		String sql = "SELECT COUNT(DISTINCT s.id) FROM sessions s JOIN session_keys k ON k.session_id = s.id"
				+ " WHERE k.name = ? AND k.value = ?" + PRIOR;
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, AgentSettingsSample.KEY_ANI);
			ps.setString(2, ani);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	/// The most recent completed calls from this number: when, how long (created
	/// to destroyed), and what was dialled if the app also keyed `dnis`.
	private List<CallerHistory.Call> sessionRecent(Connection c, String ani, int limit) throws Exception {
		String sql = "SELECT DISTINCT s.id, s.created, s.destroyed, d.value FROM sessions s"
				+ " JOIN session_keys k ON k.session_id = s.id AND k.name = ? AND k.value = ?"
				+ " LEFT JOIN session_keys d ON d.session_id = s.id AND d.name = ?"
				+ " WHERE 1 = 1" + PRIOR + " ORDER BY s.created DESC FETCH FIRST " + limit + " ROWS ONLY";
		List<CallerHistory.Call> out = new ArrayList<>();
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, AgentSettingsSample.KEY_ANI);
			ps.setString(2, ani);
			ps.setString(3, AgentSettingsSample.KEY_DNIS);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					Timestamp created = rs.getTimestamp(2);
					Timestamp destroyed = rs.getTimestamp(3);
					// A row still open is a call whose end was never recorded: no duration.
					long millis = (created != null && destroyed != null) ? destroyed.getTime() - created.getTime() : -1;
					CallerHistory.Call call = new CallerHistory.Call(
							created == null ? null : created.toInstant().toString(), millis, rs.getString(4), null);
					disposition(c, rs.getLong(1), call);
					said(c, rs.getLong(1), call);
					reviewed(c, rs.getLong(1), call);
					out.add(call);
				}
			}
		}
		return out;
	}

	/// The caller's first lines on a session, from the `callerSaid` events the
	/// sink stored (attributes as JSON: text, party, labels). Two lines is enough
	/// to recognise a story; the transcript pane shows the rest on request later.
	private void said(Connection c, long sessionId, CallerHistory.Call call) {
		String sql = "SELECT e.payload FROM events e WHERE e.session_id = ? AND e.type = 'callerSaid'"
				+ " ORDER BY e.created FETCH FIRST 6 ROWS ONLY";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setLong(1, sessionId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next() && call.said.size() < 2) {
					String payload = rs.getString(1);
					if (payload == null) {
						continue;
					}
					com.fasterxml.jackson.databind.JsonNode d = MAPPER.readTree(payload);
					if (!"caller".equals(d.path("party").asText("caller"))) {
						continue;
					}
					String text = d.path("text").asText(null);
					if (text != null && !text.isBlank()) {
						call.said.add(text.length() > 140 ? text.substring(0, 137) + "…" : text);
					}
				}
			}
		} catch (Exception e) {
			LOG.log(Level.FINE, "agent: prior lines unavailable for session " + sessionId + ": " + e.getMessage());
		}
	}

	/// The post-call review filed on a session, if any (`callReviewed`: labels,
	/// the caller line that shows them).
	private void reviewed(Connection c, long sessionId, CallerHistory.Call call) {
		String sql = "SELECT e.payload FROM events e WHERE e.session_id = ? AND e.type = 'callReviewed'"
				+ " ORDER BY e.created DESC FETCH FIRST 1 ROWS ONLY";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setLong(1, sessionId);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next() && rs.getString(1) != null) {
					com.fasterxml.jackson.databind.JsonNode d = MAPPER.readTree(rs.getString(1));
					call.reviewLabels = d.path("labels").asText(null);
					call.reviewText = d.path("text").asText(null);
				}
			}
		} catch (Exception e) {
			LOG.log(Level.FINE, "agent: review unavailable for session " + sessionId + ": " + e.getMessage());
		}
	}

	/// The latest agent disposition filed on a session, if any: the sink stores
	/// an application event's attributes as one JSON document in `events.payload`
	/// under the event's short name. Best-effort; a call without one shows as it
	/// did before.
	private void disposition(Connection c, long sessionId, CallerHistory.Call call) {
		String sql = "SELECT e.payload FROM events e WHERE e.session_id = ? AND e.type = ?"
				+ " ORDER BY e.created DESC FETCH FIRST 1 ROWS ONLY";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setLong(1, sessionId);
			ps.setString(2, DispositionService.EVENT);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					String payload = rs.getString(1);
					if (payload != null) {
						com.fasterxml.jackson.databind.JsonNode d = MAPPER.readTree(payload);
						call.outcome = d.path("outcome").asText(null);
						call.identity = d.path("identity").asText(null);
						call.action = d.path("action").asText(null);
						call.notes = d.path("notes").asText(null);
						call.agent = d.path("agent").asText(null);
						call.reasons = d.path("reasons").asText(null);
					}
				}
			}
		} catch (Exception e) {
			LOG.log(Level.FINE, "agent: disposition unavailable for session " + sessionId + ": " + e.getMessage());
		}
	}

	private int count(Connection c, String ani) throws Exception {
		String sql = "SELECT COUNT(*) FROM " + conversationTable + " WHERE FROM_NUMBER = ?";
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, ani);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	private List<CallerHistory.Call> recent(Connection c, String ani, int limit) throws Exception {
		// Newest first. FETCH FIRST is standard on Oracle/DB2/PostgreSQL; the
		// query is read-only and its failure is caught by the caller.
		String sql = "SELECT EPOCH_UTC, DURATION_MS, TO_NUMBER, CONVERSATION FROM " + conversationTable
				+ " WHERE FROM_NUMBER = ? ORDER BY EPOCH_UTC DESC FETCH FIRST " + limit + " ROWS ONLY";
		List<CallerHistory.Call> out = new ArrayList<>();
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, ani);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					Timestamp when = rs.getTimestamp(1);
					out.add(new CallerHistory.Call(
							when == null ? null : when.toInstant().toString(),
							rs.getLong(2),
							rs.getString(3),
							rs.getString(4)));
				}
			}
		}
		return out;
	}

	private List<String> priorLabels(Connection c, String ani) throws Exception {
		String sql = "SELECT DISTINCT l.LABEL FROM BLADE_LABEL l JOIN " + conversationTable
				+ " v ON v.CONVERSATION = l.CONVERSATION WHERE v.FROM_NUMBER = ?";
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, ani);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String label = rs.getString(1);
					if (label != null) {
						out.add(label);
					}
				}
			}
		} catch (Exception e) {
			// The label table may not exist on an older catalog; history without
			// labels is still useful.
			LOG.log(Level.FINE, "agent: prior labels unavailable: " + e.getMessage());
		}
		return out;
	}

	/// Topics of this caller's recent conversations, newest first: the `topic`
	/// attribute a summarizer writes to `BLADE_CONVERSATION_ATTR`. Best-effort;
	/// empty when nothing has written topics (the common case today).
	private List<String> topics(Connection c, String ani, int limit) throws Exception {
		String sql = "SELECT a.VALUE FROM BLADE_CONVERSATION_ATTR a JOIN " + conversationTable
				+ " v ON v.CONVERSATION = a.CONVERSATION WHERE v.FROM_NUMBER = ? AND a.NAME = 'topic' "
				+ "ORDER BY v.EPOCH_UTC DESC FETCH FIRST " + limit + " ROWS ONLY";
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, ani);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String topic = rs.getString(1);
					if (topic != null && !topic.isBlank()) {
						out.add(topic);
					}
				}
			}
		} catch (Exception e) {
			LOG.log(Level.FINE, "agent: topics unavailable: " + e.getMessage());
		}
		return out;
	}

	/// Add a number to the block list proxy-block reads, with a treatment and an
	/// expiry (numbers rotate, so blocks expire). Columns per proxy-block's
	/// `spam-numbers.sql`. A number already on the list is updated instead: the
	/// newer treatment and reason win and the expiry restarts, so a second agent
	/// blocking the same caller extends the block rather than bouncing off the
	/// primary key. Returns true when a row was written, false (logged) on any
	/// failure.
	public boolean blockNumber(String tn, String treatment, String reason, String markedBy, int expiryDays) {
		if (!enabled() || tn == null || tn.isBlank()) {
			return false;
		}
		String insert = "INSERT INTO " + spamTable + " (tn, treatment, reason, marked_by, expires_at) "
				+ "VALUES (?, ?, ?, ?, ?)";
		String update = "UPDATE " + spamTable + " SET treatment = ?, reason = ?, marked_by = ?, expires_at = ?"
				+ " WHERE tn = ?";
		try {
			DataSource ds = (DataSource) new InitialContext().lookup(dataSource);
			Timestamp expiry = new Timestamp(
					System.currentTimeMillis() + (long) Math.max(1, expiryDays) * 86_400_000L);
			try (Connection c = ds.getConnection()) {
				try (PreparedStatement ps = c.prepareStatement(update)) {
					ps.setString(1, treatment);
					ps.setString(2, reason);
					ps.setString(3, markedBy);
					ps.setTimestamp(4, expiry);
					ps.setString(5, tn);
					if (ps.executeUpdate() > 0) {
						return true;
					}
				} catch (Exception noTableYet) {
					// No table to update: the insert below finds out and creates it.
				}
				try (PreparedStatement ps = c.prepareStatement(insert)) {
					ps.setString(1, tn);
					ps.setString(2, treatment);
					ps.setString(3, reason);
					ps.setString(4, markedBy);
					ps.setTimestamp(5, expiry);
					ps.executeUpdate();
					return true;
				} catch (Exception noTable) {
					// The block list is proxy-block's table, applied by hand from its
					// spam-numbers.sql, and a fresh schema does not have it. Create it
					// from the same DDL and try once more; on a real failure the
					// retry's exception is the one reported.
					createBlockList(c);
					try (PreparedStatement ps = c.prepareStatement(insert)) {
						ps.setString(1, tn);
						ps.setString(2, treatment);
						ps.setString(3, reason);
						ps.setString(4, markedBy);
						ps.setTimestamp(5, expiry);
						ps.executeUpdate();
						return true;
					}
				}
			}
		} catch (Exception e) {
			AgentConsoleRegistry.log("agent: could not block " + tn + ": " + e);
			return false;
		}
	}

	/// Attach a label to a conversation: the catalog half of an agent report.
	/// `BLADE_LABEL` is the calibration/label store; this is its first writer.
	/// Returns true on a committed insert, false (logged) on any failure.
	/// The block list's DDL, the same as proxy-block's `spam-numbers.sql`
	/// (ten-digit number as the key, a treatment, who and why, and an expiry).
	/// Best-effort: an existing table, or a schema that refuses DDL, leaves the
	/// insert to fail on its own terms.
	private void createBlockList(Connection c) {
		String ddl = "CREATE TABLE " + spamTable + " (tn VARCHAR(15) NOT NULL PRIMARY KEY,"
				+ " treatment VARCHAR(16) DEFAULT 'tarpit' NOT NULL, reason VARCHAR(64), marked_by VARCHAR(64),"
				+ " marked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL)";
		try (java.sql.Statement st = c.createStatement()) {
			st.executeUpdate(ddl);
			AgentConsoleRegistry.log("agent: created the block list table " + spamTable);
		} catch (Exception e) {
			AgentConsoleRegistry.log("agent: block list table " + spamTable + " not created: " + e);
			return;
		}
		try (java.sql.Statement st = c.createStatement()) {
			st.executeUpdate("CREATE INDEX " + spamTable + "_expires ON " + spamTable + " (expires_at)");
		} catch (Exception ignore) {
			// the index is a convenience for the reaper, not for the insert
		}
	}

	public boolean label(String conversation, String label, double confidence, String source) {
		if (!enabled() || conversation == null || conversation.isBlank() || label == null) {
			return false;
		}
		// Columns per CatalogSchema: (CONVERSATION, LABEL, CONFIDENCE, SOURCE,
		// EVIDENCE_SEQUENCE), PK (CONVERSATION, LABEL, SOURCE). EVIDENCE_SEQUENCE
		// points at an utterance and is null for an agent report. A repeat report
		// by the same agent on the same conversation hits the PK and is caught.
		String sql = "INSERT INTO BLADE_LABEL (CONVERSATION, LABEL, CONFIDENCE, SOURCE) VALUES (?, ?, ?, ?)";
		try {
			DataSource ds = (DataSource) new InitialContext().lookup(dataSource);
			try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
				ps.setString(1, conversation);
				ps.setString(2, label);
				ps.setDouble(3, confidence);
				ps.setString(4, source);
				ps.executeUpdate();
				return true;
			}
		} catch (Exception e) {
			LOG.log(Level.WARNING, "agent: could not label " + conversation + " as " + label + ": " + e.getMessage());
			return false;
		}
	}
}
