package org.vorpal.blade.applications.catalog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vorpal.blade.framework.v3.jdbc.Dialect;

/// The catalog's tables, and the one piece of them the catalog makes itself.
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
/// The tables belong to the analytics schema
/// (`services/analytics/sql/<Dialect>-database-schema.sql`, migration
/// `003-call-catalog`), which `upgrade.sh` applies; the catalog only checks
/// they are there. The full-text index is the exception, because it is
/// optional and a setting turns it on: Oracle Text on Oracle, a FULLTEXT index
/// on MySQL. SQL Server's full-text search is a separate server component and
/// wants a single-column key these tables do not have, so there a search for
/// words scans, which is slow and correct, and the log says which it is.
final class CatalogSchema {

	private static final Logger LOG = Logger.getLogger(CatalogSchema.class.getName());

	static final String FULL_TEXT_INDEX = "BLADE_UTTERANCE_TXT";

	private CatalogSchema() {
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

	/// Check the tables are there and make the full-text index when it is
	/// wanted and missing. Returns whether the full-text index exists
	/// afterwards.
	static boolean ensure(Connection c, boolean fullText) throws SQLException {
		Dialect d = Dialect.of(c);
		if (!exists(c, "BLADE_CONVERSATION")) {
			throw new SQLException("the catalog tables are not in this database: run ./upgrade.sh <env>, "
					+ "which adds them to the analytics schema");
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
