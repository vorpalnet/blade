package org.vorpal.blade.framework.v3.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/// The few places hand-written SQL differs between the databases BLADE
/// supports: Oracle, MySQL and SQL Server. Everything else an application
/// writes should be plain SQL that all three take; when it cannot be, the
/// difference belongs here, named, rather than as an Oracle-only string in
/// the application.
///
/// [#STANDARD] is any other database, written as the SQL standard spells
/// it. HSQLDB, which the unit tests run on, is one.
///
/// The dialect is read from the connection ([#of]), never configured: the
/// data source already says which database it is.
public enum Dialect {

	ORACLE, MYSQL, SQLSERVER, STANDARD;

	/// The dialect of the database behind this connection.
	public static Dialect of(Connection c) throws SQLException {
		String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
		if (product.contains("oracle")) {
			return ORACLE;
		}
		if (product.contains("mysql") || product.contains("mariadb")) {
			return MYSQL;
		}
		if (product.contains("sql server")) {
			return SQLSERVER;
		}
		return STANDARD;
	}

	/// The clause that ends a query after its first `rows` rows, `rows` being a
	/// number or `?`. It follows the query's ORDER BY, which SQL Server
	/// requires.
	public String firstRows(String rows) {
		return this == MYSQL ? " LIMIT " + rows : " OFFSET 0 ROWS FETCH NEXT " + rows + " ROWS ONLY";
	}

	/// A column of text up to `length` characters, in any script. Oracle counts
	/// characters only when told to; SQL Server keeps non-Latin text only in
	/// NVARCHAR.
	public String text(int length) {
		switch (this) {
		case ORACLE:
			return "VARCHAR2(" + length + " CHAR)";
		case SQLSERVER:
			return "NVARCHAR(" + length + ")";
		default:
			return "VARCHAR(" + length + ")";
		}
	}

	/// A date and time without a zone, to the millisecond. SQL Server's
	/// TIMESTAMP is a row version, not a time.
	public String timestamp() {
		switch (this) {
		case MYSQL:
			return "DATETIME(3)";
		case SQLSERVER:
			return "DATETIME2(3)";
		default:
			return "TIMESTAMP";
		}
	}

	/// The current time as a column default for [#timestamp]. MySQL wants the
	/// default at the column's own precision.
	public String now() {
		return this == MYSQL ? "CURRENT_TIMESTAMP(3)" : "CURRENT_TIMESTAMP";
	}

	/// A whole number up to 19 digits, which a Java long fits.
	public String integer() {
		return this == ORACLE ? "NUMBER(19)" : "BIGINT";
	}

	/// A fraction.
	public String decimal() {
		return this == ORACLE ? "NUMBER" : "DOUBLE PRECISION";
	}

	/// Whether an index of this name exists on this table, in the connection's
	/// own schema.
	public boolean indexExists(Connection c, String table, String index) throws SQLException {
		String sql;
		switch (this) {
		case ORACLE:
			sql = "SELECT COUNT(*) FROM USER_INDEXES WHERE TABLE_NAME = ? AND INDEX_NAME = ?";
			break;
		case MYSQL:
			sql = "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()"
					+ " AND table_name = ? AND index_name = ?";
			break;
		case SQLSERVER:
			sql = "SELECT COUNT(*) FROM sys.indexes WHERE object_id = OBJECT_ID(?) AND name = ?";
			break;
		default:
			sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SYSTEM_INDEXINFO WHERE TABLE_NAME = ? AND INDEX_NAME = ?";
		}
		try (PreparedStatement s = c.prepareStatement(sql)) {
			s.setString(1, table);
			s.setString(2, index);
			try (ResultSet rs = s.executeQuery()) {
				return rs.next() && rs.getInt(1) > 0;
			}
		}
	}
}
