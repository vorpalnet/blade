package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashMap;
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
/// ## Threading
///
/// JDBC is synchronous by design, but we don't want to block the
/// SIP container thread. [#invoke] wraps the query in
/// [CompletableFuture#supplyAsync] using the framework's bounded
/// [Executors#DB] thread pool. The SIP thread returns immediately;
/// the query runs on a DB worker; selectors run on that same
/// worker when the row arrives.
@JsonPropertyOrder({ "type", "id", "description", "dataSource", "queryTemplate",
		"circuitBreakerCooldownSeconds", "circuitBreakerTrap", "selectors" })
public class JdbcConnector extends Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	protected String dataSource;
	protected String queryTemplate;
	protected Integer circuitBreakerCooldownSeconds;
	protected Boolean circuitBreakerTrap;

	@JsonIgnore
	private transient DataSource cachedDataSource;
	@JsonIgnore
	private transient String cachedQuery;
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
		final String sql;
		try {
			if (cachedQuery == null) {
				Path p = Paths.get(TEMPLATES_DIR, queryTemplate);
				if (!Files.exists(p)) throw new IOException("SQL template not found: " + p);
				cachedQuery = Files.readString(p);
			}
			sql = ctx.resolve(cachedQuery);
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
						Statement stmt = conn.createStatement();
						ResultSet rs = stmt.executeQuery(sql)) {
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
}
