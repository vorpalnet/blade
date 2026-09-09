package org.vorpal.blade.applications.catalog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

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
/// The DDL is Oracle's, because that is the analytics database BLADE ships
/// against; the statements are kept to what any database with `VARCHAR2`
/// spelt its own way would take. The full-text index is Oracle Text and is
/// the one optional piece: without it a search for words scans, which is
/// slow and correct, and the log says which it is.
final class CatalogSchema {

	private static final Logger LOG = Logger.getLogger(CatalogSchema.class.getName());

	private CatalogSchema() {
	}

	private static final String[] TABLES = {
			"CREATE TABLE BLADE_CONVERSATION ("
					+ "CONVERSATION VARCHAR2(64) PRIMARY KEY, CALL_ID VARCHAR2(64), EPOCH_UTC TIMESTAMP, "
					+ "DURATION_MS NUMBER, COMPLETE NUMBER(1), INCOMPLETE_REASON VARCHAR2(400), "
					+ "FROM_NUMBER VARCHAR2(64), TO_NUMBER VARCHAR2(64), UTTERANCES NUMBER, HOLDS NUMBER, MOVES NUMBER, "
					+ "KINDS VARCHAR2(400), NODE VARCHAR2(64), INDEXED_UTC TIMESTAMP, INDEX_VERSION NUMBER)",
			"CREATE INDEX BLADE_CONVERSATION_EPOCH ON BLADE_CONVERSATION (EPOCH_UTC)",
			"CREATE INDEX BLADE_CONVERSATION_CALL ON BLADE_CONVERSATION (CALL_ID)",
			"CREATE INDEX BLADE_CONVERSATION_FROM ON BLADE_CONVERSATION (FROM_NUMBER)",
			"CREATE INDEX BLADE_CONVERSATION_TO ON BLADE_CONVERSATION (TO_NUMBER)",
			"CREATE TABLE BLADE_CONVERSATION_ATTR ("
					+ "CONVERSATION VARCHAR2(64), NAME VARCHAR2(64), VALUE VARCHAR2(400), "
					+ "PRIMARY KEY (CONVERSATION, NAME))",
			"CREATE INDEX BLADE_CONVERSATION_ATTR_NV ON BLADE_CONVERSATION_ATTR (NAME, VALUE)",
			"CREATE TABLE BLADE_UTTERANCE ("
					+ "CONVERSATION VARCHAR2(64), TRANSCRIPT VARCHAR2(32), SEQUENCE NUMBER, PARTY VARCHAR2(64), "
					+ "START_MS NUMBER, END_MS NUMBER, TEXT VARCHAR2(4000), KINDS VARCHAR2(200), "
					+ "PRIMARY KEY (CONVERSATION, TRANSCRIPT, SEQUENCE))",
			"CREATE TABLE BLADE_PROTECTED ("
					+ "CONVERSATION VARCHAR2(64), KIND VARCHAR2(64), DIGEST VARCHAR2(64), "
					+ "PRIMARY KEY (CONVERSATION, KIND, DIGEST))",
			"CREATE INDEX BLADE_PROTECTED_DIGEST ON BLADE_PROTECTED (DIGEST)",
			"CREATE TABLE BLADE_LABEL ("
					+ "CONVERSATION VARCHAR2(64), LABEL VARCHAR2(64), CONFIDENCE NUMBER, SOURCE VARCHAR2(64), "
					+ "EVIDENCE_SEQUENCE NUMBER, PRIMARY KEY (CONVERSATION, LABEL, SOURCE))",
			"CREATE INDEX BLADE_LABEL_LABEL ON BLADE_LABEL (LABEL)" };

	private static final String FULL_TEXT = "CREATE INDEX BLADE_UTTERANCE_TXT ON BLADE_UTTERANCE (TEXT) "
			+ "INDEXTYPE IS CTXSYS.CONTEXT PARAMETERS ('SYNC (ON COMMIT)')";

	/// Create whatever is missing. Returns whether the full-text index exists
	/// afterwards.
	static boolean ensure(Connection c, boolean fullText) throws SQLException {
		if (!exists(c, "BLADE_CONVERSATION")) {
			for (String ddl : TABLES) {
				try (Statement s = c.createStatement()) {
					s.execute(ddl);
				}
			}
			LOG.info("catalog: created the catalog tables");
		}
		boolean indexed = indexExists(c, "BLADE_UTTERANCE_TXT");
		if (fullText && !indexed) {
			try (Statement s = c.createStatement()) {
				s.execute(FULL_TEXT);
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

	private static boolean indexExists(Connection c, String index) {
		try (Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME = '" + index + "'")) {
			return rs.next() && rs.getInt(1) > 0;
		} catch (SQLException e) {
			return false;
		}
	}
}
