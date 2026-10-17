package org.vorpal.blade.applications.catalog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v3.jdbc.Dialect;

/// The catalog's tables, created on first use and left alone afterwards.
///
/// Five tables, all keyed by conversation, all rebuildable from the archive:
///
/// | table                       | one row per                                   |
/// |-----------------------------|-----------------------------------------------|
/// | `BLADE_CONVERSATION`        | conversation: who, when, how long, the counts |
/// | `BLADE_CONVERSATION_ATTR`   | attribute the recording carried               |
/// | `BLADE_UTTERANCE`           | utterance, redacted text                      |
/// | `BLADE_PROTECTED`           | protected value found, as a keyed hash        |
/// | `BLADE_LABEL`               | category a classifier assigned, with evidence |
///
/// The DDL is written once and typed per database ([Dialect]): Oracle, MySQL
/// and SQL Server each get columns that hold text in any script and a time to
/// the millisecond. The full-text index is the one optional piece: Oracle Text
/// on Oracle, a FULLTEXT index on MySQL. SQL Server's full-text search is a
/// separate server component and wants a single-column key these tables do not
/// have, so there a search for words scans, which is slow and correct, and the
/// log says which it is.
final class CatalogSchema {

	private static final Logger LOG = Logger.getLogger(CatalogSchema.class.getName());

	static final String FULL_TEXT_INDEX = "BLADE_UTTERANCE_TXT";

	private CatalogSchema() {
	}

	static String[] tables(Dialect d) {
		String id = d.text(64), n = d.integer(), t = d.timestamp();
		return new String[] {
				"CREATE TABLE BLADE_CONVERSATION ("
						+ "CONVERSATION " + id + " NOT NULL PRIMARY KEY, CALL_ID " + id + ", EPOCH_UTC " + t + ", "
						+ "DURATION_MS " + n + ", COMPLETE SMALLINT, INCOMPLETE_REASON " + d.text(400) + ", "
						+ "FROM_NUMBER " + id + ", TO_NUMBER " + id + ", UTTERANCES " + n + ", HOLDS " + n + ", MOVES " + n + ", "
						+ "KINDS " + d.text(400) + ", NODE " + id + ", INDEXED_UTC " + t + ", INDEX_VERSION " + n + ")",
				"CREATE INDEX BLADE_CONVERSATION_EPOCH ON BLADE_CONVERSATION (EPOCH_UTC)",
				"CREATE INDEX BLADE_CONVERSATION_CALL ON BLADE_CONVERSATION (CALL_ID)",
				"CREATE INDEX BLADE_CONVERSATION_FROM ON BLADE_CONVERSATION (FROM_NUMBER)",
				"CREATE INDEX BLADE_CONVERSATION_TO ON BLADE_CONVERSATION (TO_NUMBER)",
				"CREATE TABLE BLADE_CONVERSATION_ATTR ("
						+ "CONVERSATION " + id + " NOT NULL, NAME " + id + " NOT NULL, VALUE " + d.text(400) + ", "
						+ "PRIMARY KEY (CONVERSATION, NAME))",
				"CREATE INDEX BLADE_CONVERSATION_ATTR_NV ON BLADE_CONVERSATION_ATTR (NAME, VALUE)",
				"CREATE TABLE BLADE_UTTERANCE ("
						+ "CONVERSATION " + id + " NOT NULL, TRANSCRIPT " + d.text(32) + " NOT NULL, SEQUENCE " + n + " NOT NULL, "
						+ "PARTY " + id + ", START_MS " + n + ", END_MS " + n + ", TEXT " + d.text(4000) + ", KINDS " + d.text(200) + ", "
						+ "PRIMARY KEY (CONVERSATION, TRANSCRIPT, SEQUENCE))",
				"CREATE TABLE BLADE_PROTECTED ("
						+ "CONVERSATION " + id + " NOT NULL, KIND " + id + " NOT NULL, DIGEST " + id + " NOT NULL, "
						+ "PRIMARY KEY (CONVERSATION, KIND, DIGEST))",
				"CREATE INDEX BLADE_PROTECTED_DIGEST ON BLADE_PROTECTED (DIGEST)",
				"CREATE TABLE BLADE_LABEL ("
						+ "CONVERSATION " + id + " NOT NULL, LABEL " + id + " NOT NULL, CONFIDENCE " + d.decimal() + ", SOURCE " + id + " NOT NULL, "
						+ "EVIDENCE_SEQUENCE " + n + ", PRIMARY KEY (CONVERSATION, LABEL, SOURCE))",
				"CREATE INDEX BLADE_LABEL_LABEL ON BLADE_LABEL (LABEL)" };
	}

	/// The full-text index on the utterances, or null where the database has
	/// none the catalog can build.
	static String fullTextIndex(Dialect d) {
		switch (d) {
		case ORACLE:
			return "CREATE INDEX " + FULL_TEXT_INDEX + " ON BLADE_UTTERANCE (TEXT) "
					+ "INDEXTYPE IS CTXSYS.CONTEXT PARAMETERS ('SYNC (ON COMMIT)')";
		case MYSQL:
			return "CREATE FULLTEXT INDEX " + FULL_TEXT_INDEX + " ON BLADE_UTTERANCE (TEXT)";
		default:
			return null;
		}
	}

	/// Create whatever is missing. Returns whether the full-text index exists
	/// afterwards.
	static boolean ensure(Connection c, boolean fullText) throws SQLException {
		Dialect d = Dialect.of(c);
		if (!exists(c, "BLADE_CONVERSATION")) {
			for (String ddl : tables(d)) {
				try (Statement s = c.createStatement()) {
					s.execute(ddl);
				}
			}
			LOG.info("catalog: created the catalog tables (" + d + ")");
		}
		boolean indexed = d.indexExists(c, "BLADE_UTTERANCE", FULL_TEXT_INDEX);
		String ddl = fullTextIndex(d);
		if (fullText && !indexed && ddl == null) {
			LOG.info("catalog: no full-text index on " + d + "; a search for words will scan");
		} else if (fullText && !indexed) {
			try (Statement s = c.createStatement()) {
				s.execute(ddl);
				indexed = true;
				LOG.info("catalog: created the full-text index on utterances");
			} catch (SQLException e) {
				LOG.log(Level.WARNING, "catalog: no full-text index; a search for words will scan: " + e.getMessage());
			}
		}
		return indexed;
	}

	private static boolean exists(Connection c, String table) throws SQLException {
		try (ResultSet rs = c.getMetaData().getTables(null, null, table, null)) {
			if (rs.next()) {
				return true;
			}
		}
		// Some drivers want the schema; ask by name as a fallback.
		try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 1 FROM " + table + " WHERE 1=0")) {
			return true;
		} catch (SQLException notThere) {
			return false;
		}
	}
}
