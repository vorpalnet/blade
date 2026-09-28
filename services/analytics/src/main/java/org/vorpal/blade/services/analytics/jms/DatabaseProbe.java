package org.vorpal.blade.services.analytics.jms;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Whether anybody has set up the analytics database: the `jdbc/BladeAnalytics`
/// data source exists and its `events` table is there to insert into.
///
/// **Not set up is not an error.** A database costs money, and a domain that
/// has not bought one should hear nothing about it: the sink simply does not
/// subscribe, and says so once at INFO. **Set up but down is a different
/// thing** — a connection that fails counts as set up, and the sink's own outage
/// handling (pause, health check, SEVERE) takes over as before.
///
/// Asked on every reconcile of the subscription; a negative answer is
/// remembered for [#RECHECK_MS], so a missing database costs one lookup a
/// minute, and one provisioned later is found without a redeploy.
final class DatabaseProbe {

	static final String DATA_SOURCE = "jdbc/BladeAnalytics";

	/// How long a "not set up" answer stands before looking again.
	static final long RECHECK_MS = 60_000L;

	private static volatile Boolean configured;
	private static volatile long checkedAt;

	private DatabaseProbe() {
	}

	/// True when the data source resolves and its `events` table exists, or the
	/// database cannot be reached at all (an outage, which the sink reports).
	static boolean configured() {
		Boolean known = configured;
		if (Boolean.TRUE.equals(known)) {
			return true;
		}
		long now = System.currentTimeMillis();
		if (known != null && now - checkedAt < RECHECK_MS) {
			return false;
		}
		checkedAt = now;
		String missing = check();
		boolean ready = (missing == null);
		if (known == null || ready != known.booleanValue()) {
			info(ready ? "analytics: database " + DATA_SOURCE + " is set up; recording events"
					: "analytics: no database set up (" + missing + "); events are not recorded");
		}
		configured = Boolean.valueOf(ready);
		return ready;
	}

	/// Null when set up, else what is missing.
	private static String check() {
		DataSource ds;
		try {
			ds = (DataSource) new InitialContext().lookup(DATA_SOURCE);
		} catch (NamingException e) {
			return DATA_SOURCE + " is not defined";
		}
		try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
			st.executeQuery("SELECT 1 FROM events WHERE 1 = 0").close();
			return null;
		} catch (SQLException e) {
			// SQLSTATE class 42 is "no such object" on all three dialects (Oracle
			// ORA-00942, MySQL 42S02, SQL Server 42S02 "Invalid object name").
			// Anything else is a database that exists and is failing: an outage.
			String state = e.getSQLState();
			if (e instanceof SQLSyntaxErrorException || (state != null && state.startsWith("42"))) {
				return "its events table does not exist; run the analytics schema";
			}
			return null;
		}
	}

	private static void info(String message) {
		org.vorpal.blade.framework.v2.logging.Logger sip = SettingsManager.getSipLogger();
		if (sip != null) {
			sip.info(message);
		} else {
			java.util.logging.Logger.getLogger(DatabaseProbe.class.getName()).info(message);
		}
	}
}
