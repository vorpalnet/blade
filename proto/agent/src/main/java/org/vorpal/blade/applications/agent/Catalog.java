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
/// The schema is proxy-block/catalog's: `BLADE_CONVERSATION(FROM_NUMBER,
/// TO_NUMBER, EPOCH_UTC, DURATION_MS, CONVERSATION, ...)` and `BLADE_LABEL(
/// CONVERSATION, LABEL, CONFIDENCE, SOURCE, ...)`. `BLADE_LABEL` is the catalog's
/// label store, which nothing wrote until agent reports.
public final class Catalog {

	private static final Logger LOG = Logger.getLogger(Catalog.class.getName());

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
				int count = count(c, ani);
				List<CallerHistory.Call> recent = recent(c, ani, Math.max(1, limit));
				List<String> labels = priorLabels(c, ani);
				List<String> topics = topics(c, ani, Math.max(1, limit));
				return new CallerHistory(count, recent, labels, topics);
			}
		} catch (Exception e) {
			LOG.log(Level.FINE, "agent: caller history unavailable for " + ani + ": " + e.getMessage(), e);
			return CallerHistory.EMPTY;
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
	/// `spam-numbers.sql`. Returns true on a committed insert, false (logged) on
	/// any failure. A number already blocked hits the primary key and is caught.
	public boolean blockNumber(String tn, String treatment, String reason, String markedBy, int expiryDays) {
		if (!enabled() || tn == null || tn.isBlank()) {
			return false;
		}
		String sql = "INSERT INTO " + spamTable + " (tn, treatment, reason, marked_by, expires_at) "
				+ "VALUES (?, ?, ?, ?, ?)";
		try {
			DataSource ds = (DataSource) new InitialContext().lookup(dataSource);
			try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
				ps.setString(1, tn);
				ps.setString(2, treatment);
				ps.setString(3, reason);
				ps.setString(4, markedBy);
				long expiry = System.currentTimeMillis() + (long) Math.max(1, expiryDays) * 86_400_000L;
				ps.setTimestamp(5, new Timestamp(expiry));
				ps.executeUpdate();
				return true;
			}
		} catch (Exception e) {
			LOG.log(Level.WARNING, "agent: could not block " + tn + ": " + e.getMessage());
			return false;
		}
	}

	/// Attach a label to a conversation: the catalog half of an agent report.
	/// `BLADE_LABEL` is the calibration/label store; this is its first writer.
	/// Returns true on a committed insert, false (logged) on any failure.
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
