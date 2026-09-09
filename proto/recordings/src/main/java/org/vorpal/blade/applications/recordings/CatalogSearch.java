package org.vorpal.blade.applications.recordings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.naming.InitialContext;
import javax.sql.DataSource;

/// A search over the call catalog: the rows the catalog application wrote
/// from the archive, queried by the facts a supervisor has and by the words
/// that were said.
///
/// ## What a query is
///
/// A [Query] is a set of filters, all optional, all combined with AND: a day
/// range, a calling or called number by prefix, attributes by exact value
/// (department, queue, whatever the recorder stamped), redaction kinds found
/// (a call in which a card was taken), a call id, and words. Words search the
/// redacted rendition through the database's full-text index when the
/// catalog built one, and by a scan of the same column when it did not. A
/// protected value is searched by its keyed hash, never by its text, and only
/// the review API decides who may ask that.
///
/// ## What comes back
///
/// Candidates, in recency order, with the facts the access policy needs to
/// decide on each one: every attribute the recording carried. The API
/// evaluates each candidate the way the day listing does and returns only
/// those the caller may list. A snippet is the first utterance that matched
/// the words, from the redacted rendition, so a hit never shows a number.
///
/// The SQL is built here and nowhere else, so the shape of a query can be
/// tested without a database.
final class CatalogSearch {

	static final int MAX_HITS = 100;

	/// The filters of one search.
	static final class Query {
		String words;
		LocalDate from;
		LocalDate to;
		String number;
		String call;
		final Map<String, String> attributes = new LinkedHashMap<>();
		final List<String> kinds = new ArrayList<>();
		String protectedDigest;
		int limit = MAX_HITS;
	}

	/// One conversation the query matched.
	static final class Hit {
		String conversation;
		String call;
		String epochUtc;
		long durationMillis;
		boolean complete;
		String from;
		String to;
		int utterances;
		int holds;
		int moves;
		String kinds;
		final Map<String, String> attributes = new LinkedHashMap<>();
		String snippet;
		Integer snippetSequence;
		Long snippetMillis;
	}

	/// The statement and its parameters, in order.
	static final class Prepared {
		final String sql;
		final List<Object> params;

		Prepared(String sql, List<Object> params) {
			this.sql = sql;
			this.params = params;
		}
	}

	private CatalogSearch() {
	}

	/// Build the candidate query. `fullText` says whether the words go
	/// through the full-text index or a scan.
	static Prepared prepare(Query q, boolean fullText) {
		StringBuilder sql = new StringBuilder("SELECT c.CONVERSATION, c.CALL_ID, c.EPOCH_UTC, c.DURATION_MS, c.COMPLETE, "
				+ "c.FROM_NUMBER, c.TO_NUMBER, c.UTTERANCES, c.HOLDS, c.MOVES, c.KINDS FROM BLADE_CONVERSATION c WHERE 1=1");
		List<Object> params = new ArrayList<>();
		if (q.from != null) {
			sql.append(" AND c.EPOCH_UTC >= ?");
			params.add(Timestamp.from(q.from.atStartOfDay(ZoneOffset.UTC).toInstant()));
		}
		if (q.to != null) {
			sql.append(" AND c.EPOCH_UTC < ?");
			params.add(Timestamp.from(q.to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
		}
		if (q.number != null && !q.number.isEmpty()) {
			sql.append(" AND (c.FROM_NUMBER LIKE ? OR c.TO_NUMBER LIKE ?)");
			String prefix = digits(q.number) + "%";
			params.add(prefix);
			params.add(prefix);
		}
		if (q.call != null && !q.call.isEmpty()) {
			sql.append(" AND c.CALL_ID = ?");
			params.add(q.call);
		}
		for (Map.Entry<String, String> e : q.attributes.entrySet()) {
			sql.append(" AND EXISTS (SELECT 1 FROM BLADE_CONVERSATION_ATTR a WHERE a.CONVERSATION = c.CONVERSATION"
					+ " AND a.NAME = ? AND a.VALUE = ?)");
			params.add(e.getKey());
			params.add(e.getValue());
		}
		for (String kind : q.kinds) {
			sql.append(" AND EXISTS (SELECT 1 FROM BLADE_UTTERANCE k WHERE k.CONVERSATION = c.CONVERSATION"
					+ " AND (k.KINDS = ? OR k.KINDS LIKE ? OR k.KINDS LIKE ? OR k.KINDS LIKE ?))");
			params.add(kind);
			params.add(kind + ",%");
			params.add("%," + kind);
			params.add("%," + kind + ",%");
		}
		if (q.protectedDigest != null && !q.protectedDigest.isEmpty()) {
			sql.append(" AND EXISTS (SELECT 1 FROM BLADE_PROTECTED p WHERE p.CONVERSATION = c.CONVERSATION AND p.DIGEST = ?)");
			params.add(q.protectedDigest);
		}
		if (q.words != null && !q.words.trim().isEmpty()) {
			if (fullText) {
				sql.append(" AND EXISTS (SELECT 1 FROM BLADE_UTTERANCE u WHERE u.CONVERSATION = c.CONVERSATION"
						+ " AND CONTAINS(u.TEXT, ?, 1) > 0)");
				params.add(textQuery(q.words));
			} else {
				for (String word : words(q.words)) {
					sql.append(" AND EXISTS (SELECT 1 FROM BLADE_UTTERANCE u WHERE u.CONVERSATION = c.CONVERSATION"
							+ " AND UPPER(u.TEXT) LIKE ?)");
					params.add("%" + word.toUpperCase(Locale.ROOT) + "%");
				}
			}
		}
		sql.append(" ORDER BY c.EPOCH_UTC DESC FETCH FIRST ? ROWS ONLY");
		params.add(Math.max(1, Math.min(q.limit, MAX_HITS)));
		return new Prepared(sql.toString(), params);
	}

	/// The words of a search, for the scan and for the snippet.
	static List<String> words(String text) {
		List<String> out = new ArrayList<>();
		for (String w : text.trim().split("\\s+")) {
			String clean = w.replaceAll("[^\\p{L}\\p{N}'-]", "");
			if (!clean.isEmpty()) {
				out.add(clean);
			}
		}
		return out;
	}

	/// The full-text form of a search: every word required, each escaped so
	/// the query language's own operators inside a word are just characters.
	static String textQuery(String text) {
		StringBuilder q = new StringBuilder();
		for (String w : words(text)) {
			if (q.length() > 0) {
				q.append(" AND ");
			}
			q.append('{').append(w.replace("}", "")).append('}');
		}
		return q.toString();
	}

	private static String digits(String number) {
		StringBuilder d = new StringBuilder();
		for (char c : number.toCharArray()) {
			if (Character.isLetterOrDigit(c) || c == '+' || c == '*' || c == '#') {
				d.append(c);
			}
		}
		return d.toString();
	}

	/// Run the query and gather the hits with their attributes and snippets.
	static List<Hit> run(String dataSource, Query q, boolean fullText) throws Exception {
		DataSource ds = (DataSource) new InitialContext().lookup(dataSource);
		List<Hit> hits = new ArrayList<>();
		try (Connection c = ds.getConnection()) {
			Prepared p = prepare(q, fullText);
			try (PreparedStatement s = c.prepareStatement(p.sql)) {
				for (int i = 0; i < p.params.size(); i++) {
					s.setObject(i + 1, p.params.get(i));
				}
				try (ResultSet rs = s.executeQuery()) {
					while (rs.next()) {
						Hit h = new Hit();
						h.conversation = rs.getString(1);
						h.call = rs.getString(2);
						Timestamp t = rs.getTimestamp(3);
						h.epochUtc = (t == null) ? null : t.toInstant().toString();
						h.durationMillis = rs.getLong(4);
						h.complete = rs.getInt(5) == 1;
						h.from = rs.getString(6);
						h.to = rs.getString(7);
						h.utterances = rs.getInt(8);
						h.holds = rs.getInt(9);
						h.moves = rs.getInt(10);
						h.kinds = rs.getString(11);
						hits.add(h);
					}
				}
			}
			try (PreparedStatement s = c.prepareStatement(
					"SELECT NAME, VALUE FROM BLADE_CONVERSATION_ATTR WHERE CONVERSATION = ?")) {
				for (Hit h : hits) {
					s.setString(1, h.conversation);
					try (ResultSet rs = s.executeQuery()) {
						while (rs.next()) {
							h.attributes.put(rs.getString(1), rs.getString(2));
						}
					}
				}
			}
			if (q.words != null && !q.words.trim().isEmpty()) {
				String snippetSql = fullText
						? "SELECT SEQUENCE, START_MS, TEXT FROM BLADE_UTTERANCE WHERE CONVERSATION = ? AND CONTAINS(TEXT, ?, 1) > 0"
								+ " ORDER BY SEQUENCE FETCH FIRST 1 ROWS ONLY"
						: "SELECT SEQUENCE, START_MS, TEXT FROM BLADE_UTTERANCE WHERE CONVERSATION = ? AND UPPER(TEXT) LIKE ?"
								+ " ORDER BY SEQUENCE FETCH FIRST 1 ROWS ONLY";
				String needle = fullText ? textQuery(q.words)
						: "%" + (words(q.words).isEmpty() ? "" : words(q.words).get(0).toUpperCase(Locale.ROOT)) + "%";
				try (PreparedStatement s = c.prepareStatement(snippetSql)) {
					for (Hit h : hits) {
						s.setString(1, h.conversation);
						s.setString(2, needle);
						try (ResultSet rs = s.executeQuery()) {
							if (rs.next()) {
								h.snippetSequence = rs.getInt(1);
								h.snippetMillis = rs.getLong(2);
								h.snippet = rs.getString(3);
							}
						}
					}
				}
			}
		}
		return hits;
	}

	/// Whether the catalog built its full-text index, asked of the database
	/// once per process.
	private static volatile Boolean fullTextIndexed;

	static boolean fullText(String dataSource) {
		Boolean known = fullTextIndexed;
		if (known != null) {
			return known;
		}
		boolean indexed = false;
		try {
			DataSource ds = (DataSource) new InitialContext().lookup(dataSource);
			try (Connection c = ds.getConnection();
					PreparedStatement s = c.prepareStatement(
							"SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME = 'BLADE_UTTERANCE_TXT'");
					ResultSet rs = s.executeQuery()) {
				indexed = rs.next() && rs.getInt(1) > 0;
			}
		} catch (Exception e) {
			indexed = false;
		}
		fullTextIndexed = indexed;
		return indexed;
	}

	/// The recording id a conversation is filed under: the same identifier
	/// with the timestamp half upper-cased, the inverse of
	/// [TranscriptView#conversationOf].
	static String recordingIdOf(String conversation) {
		return conversation == null ? null : conversation.toUpperCase(Locale.ROOT);
	}

	static Instant epochOrNull(String iso) {
		try {
			return iso == null ? null : Instant.parse(iso);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
