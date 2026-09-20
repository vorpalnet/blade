package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import javax.naming.InitialContext;
import javax.sql.DataSource;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.Executors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// JDBC connector. Runs a SQL query through a JNDI-bound
/// [DataSource] and passes the first row as a `Map<String,String>`
/// (column name → string value) to each
/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector].
///
/// A SQL template missing from `_templates/` on disk is copied there on first
/// use from the WAR's `src/main/resources/_templates/`, the same bootstrap
/// [RestConnector] describes; an existing file is never overwritten.
///
/// ## Threading
///
/// JDBC is synchronous by design, but we don't want to block the
/// SIP container thread. [#invoke] wraps the query in
/// [CompletableFuture#supplyAsync] using the framework's bounded
/// [Executors#DB] thread pool. The SIP thread returns immediately;
/// the query runs on a DB worker; selectors run on that same
/// worker when the row arrives.
///
/// ## Placeholders are bound, never pasted
///
/// Each `${var}` in the template becomes a `?` bound as a
/// [PreparedStatement] parameter, so a value taken from the SIP message
/// cannot change the query. A caller can put `'` in a SIP user part, and
/// with pasted text `WHERE tn = '${user}'` let them rewrite the WHERE
/// clause. A placeholder that fills a whole string literal, `'${user}'`,
/// binds the same way, so templates written before binding still load.
///
/// A placeholder can therefore stand only where a value can: not a table or
/// column name, and not part of a longer literal. `LIKE '%${user}%'` fails
/// at load; write it with the database's concatenation, for example
/// `LIKE '%' || ${user} || '%'`. A placeholder that resolves to nothing binds
/// SQL `NULL`.
@JsonPropertyOrder({ "type", "id", "description", "dataSource", "queryTemplate", "queryTimeoutSeconds",
		"circuitBreakerCooldownSeconds", "circuitBreakerTrap", "selectors" })
public class JdbcConnector extends Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	protected String dataSource;
	protected String queryTemplate;
	protected Integer circuitBreakerCooldownSeconds;
	protected Boolean circuitBreakerTrap;
	protected Integer queryTimeoutSeconds;

	@JsonIgnore
	private transient DataSource cachedDataSource;
	@JsonIgnore
	private transient PreparedQuery cachedQuery;
	@JsonIgnore
	private transient CircuitBreaker breaker = new CircuitBreaker();

	public JdbcConnector() {
	}

	@JsonPropertyDescription("JNDI name of the WebLogic JDBC DataSource, e.g. jdbc/CustomerDS")
	public String getDataSource() { return dataSource; }
	public void setDataSource(String dataSource) { this.dataSource = dataSource; }

	@JsonPropertyDescription("SQL template filename in _templates/ (supports ${var})")
	public String getQueryTemplate() { return queryTemplate; }
	public void setQueryTemplate(String queryTemplate) { this.queryTemplate = queryTemplate; }

	@JsonPropertyDescription("Seconds a query may run before the driver cancels it and the call continues to its default route. Default 5; 0 waits forever.")
	public Integer getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
	public void setQueryTimeoutSeconds(Integer queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }

	@JsonPropertyDescription("Circuit breaker: after a failed query (datasource down, connection or SQL error), "
			+ "suppress further queries for N seconds and let routing fall to its default route. Prevents hammering "
			+ "a down database and stops every call from eating the connection timeout during an outage. 0 or empty "
			+ "disables it (default).")
	public Integer getCircuitBreakerCooldownSeconds() { return circuitBreakerCooldownSeconds; }
	public void setCircuitBreakerCooldownSeconds(Integer circuitBreakerCooldownSeconds) {
		this.circuitBreakerCooldownSeconds = circuitBreakerCooldownSeconds;
	}

	@JsonPropertyDescription("When the circuit breaker opens or recovers, emit one SNMP trap on each edge (down, "
			+ "then up). Requires the WebLogic SNMP agent enabled with a trap destination (see the Tuning app). "
			+ "Default false.")
	public Boolean getCircuitBreakerTrap() { return circuitBreakerTrap; }
	public void setCircuitBreakerTrap(Boolean circuitBreakerTrap) { this.circuitBreakerTrap = circuitBreakerTrap; }

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		if (dataSource == null || queryTemplate == null) {
			return CompletableFuture.completedFuture(null);
		}

		final String connectorId = id;
		final Logger sipLogger = SettingsManager.getSipLogger();

		final javax.servlet.sip.SipServletRequest sipReq = ctx.getRequest();

		final boolean breakerEnabled = circuitBreakerCooldownSeconds != null && circuitBreakerCooldownSeconds > 0;
		if (breakerEnabled && breaker.isOpen()) {
			// OPEN — skip the query. Selectors don't run, so the iRouter falls to
			// its default route. No per-call connection-timeout wait while the
			// datasource is known-down.
			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine(sipReq, tag() + " circuit open — skipping query, routing to default");
			}
			return CompletableFuture.completedFuture(null);
		}

		// Resolve the SQL on the calling thread (cheap) but execute the query
		// on the DB executor so the SIP container thread is released.
		final PreparedQuery query;
		final List<String> values;
		try {
			if (cachedQuery == null) {
				Path p = Paths.get(TEMPLATES_DIR, queryTemplate);
				if (!Files.exists(p)) materializeBundledTemplate(queryTemplate, p);
				if (!Files.exists(p)) throw new IOException("SQL template not found on disk or in the WAR: " + p);
				cachedQuery = PreparedQuery.compile(Files.readString(p));
			}
			query = cachedQuery;
			values = query.values(ctx);
		} catch (Exception e) {
			sipLogger.warning(sipReq, "JdbcConnector[" + connectorId + "] template load failed: " + e.getMessage());
			return CompletableFuture.completedFuture(null);
		}

		return CompletableFuture.supplyAsync(() -> {
			try {
				if (cachedDataSource == null) {
					cachedDataSource = (DataSource) new InitialContext().lookup(dataSource);
				}
				try (Connection conn = cachedDataSource.getConnection();
						PreparedStatement stmt = query.prepare(conn, values, (queryTimeoutSeconds != null) ? queryTimeoutSeconds : 5);
						ResultSet rs = stmt.executeQuery()) {
					// The datasource was reachable and the query ran — that's a
					// success regardless of whether any row came back.
					if (breakerEnabled) breaker.recordSuccess(Boolean.TRUE.equals(circuitBreakerTrap), sipReq, tag());
					if (rs.next()) {
						Map<String, String> row = new LinkedHashMap<>();
						ResultSetMetaData md = rs.getMetaData();
						for (int i = 1; i <= md.getColumnCount(); i++) {
							String col = md.getColumnLabel(i);
							String val = rs.getString(i);
							if (val != null) row.put(col, val);
						}
						return row;
					}
				}
			} catch (Exception e) {
				sipLogger.warning(sipReq, "JdbcConnector[" + connectorId + "] query failed: " + e.getMessage());
				if (breakerEnabled) breaker.recordFailure(circuitBreakerCooldownSeconds,
						Boolean.TRUE.equals(circuitBreakerTrap), sipReq, tag(), e.getMessage());
			}
			return null;
		}, Executors.DB).thenAccept(row -> {
			if (row == null) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(sipReq, "JdbcConnector[" + connectorId + "] no rows");
				}
				return;
			}
			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(sipReq, "JdbcConnector[" + connectorId + "] row=" + row.keySet());
			}
			runSelectors(ctx, row);
		});
	}

	/// A query template compiled once: SQL text with `?` in place of each
	/// `${var}`, and the placeholders in bind order.
	static final class PreparedQuery {
		final String sql;
		final List<String> placeholders;

		private PreparedQuery(String sql, List<String> placeholders) {
			this.sql = sql;
			this.placeholders = Collections.unmodifiableList(placeholders);
		}

		/// Scans `template` for `${...}` outside string literals, and for string
		/// literals that are exactly one placeholder. Quoted identifiers and
		/// comments are copied untouched, so an apostrophe in a comment is harmless. Throws
		/// [IllegalArgumentException] for a placeholder inside a longer literal.
		static PreparedQuery compile(String template) {
			StringBuilder sql = new StringBuilder();
			List<String> placeholders = new ArrayList<>();
			int i = 0;
			int n = template.length();
			while (i < n) {
				char c = template.charAt(i);
				if (c == '\'') {
					int close = literalEnd(template, i);
					String body = template.substring(i + 1, close);
					String name = wholePlaceholder(body);
					if (name != null) {
						sql.append('?');
						placeholders.add(name);
					} else if (body.contains("${")) {
						throw new IllegalArgumentException("placeholder inside the string literal '" + body
								+ "'; bind it as a whole value and concatenate in SQL instead");
					} else {
						sql.append(template, i, close + 1);
					}
					i = close + 1;
				} else if (c == '"') {
					int close = template.indexOf('"', i + 1);
					int end = (close < 0) ? n : close + 1;
					sql.append(template, i, end);
					i = end;
				} else if (c == '-' && template.startsWith("--", i)) {
					int eol = template.indexOf('\n', i);
					int end = (eol < 0) ? n : eol;
					sql.append(template, i, end);
					i = end;
				} else if (c == '/' && template.startsWith("/*", i)) {
					int close = template.indexOf("*/", i + 2);
					int end = (close < 0) ? n : close + 2;
					sql.append(template, i, end);
					i = end;
				} else if (c == '$' && i + 1 < n && template.charAt(i + 1) == '{') {
					int end = template.indexOf('}', i + 2);
					if (end < 0) throw new IllegalArgumentException("unterminated ${ in query template");
					sql.append('?');
					placeholders.add(template.substring(i + 2, end));
					i = end + 1;
				} else {
					sql.append(c);
					i++;
				}
			}
			return new PreparedQuery(sql.toString(), placeholders);
		}

		/// Index of the quote closing the literal opened at `open`; `''` is an
		/// escaped quote inside it.
		private static int literalEnd(String s, int open) {
			int i = open + 1;
			while (i < s.length()) {
				if (s.charAt(i) == '\'') {
					if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
						i += 2;
						continue;
					}
					return i;
				}
				i++;
			}
			throw new IllegalArgumentException("unterminated string literal in query template");
		}

		private static String wholePlaceholder(String body) {
			if (body.startsWith("${") && body.endsWith("}") && body.indexOf('}') == body.length() - 1) {
				return body.substring(2, body.length() - 1);
			}
			return null;
		}

		/// The value for each placeholder, in bind order; null when it does not
		/// resolve.
		List<String> values(Context ctx) {
			List<String> out = new ArrayList<>(placeholders.size());
			for (String name : placeholders) {
				String placeholder = "${" + name + "}";
				String v = ctx.resolve(placeholder);
				out.add(placeholder.equals(v) ? null : v);
			}
			return out;
		}

		PreparedStatement prepare(Connection conn, List<String> values, int timeoutSeconds) throws java.sql.SQLException {
			PreparedStatement stmt = conn.prepareStatement(sql);
			try {
				stmt.setQueryTimeout(Math.max(timeoutSeconds, 0));
				for (int i = 0; i < values.size(); i++) {
					stmt.setString(i + 1, values.get(i));
				}
			} catch (java.sql.SQLException e) {
				stmt.close();
				throw e;
			}
			return stmt;
		}
	}
}
