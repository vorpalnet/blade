package org.vorpal.blade.framework.v3.configuration.adapters;

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

/// JDBC adapter. Runs a SQL query through a JNDI-bound
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
@JsonPropertyOrder({ "type", "id", "description", "dataSource", "queryTemplate", "selectors" })
public class JdbcAdapter extends Adapter implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	protected String dataSource;
	protected String queryTemplate;

	@JsonIgnore
	private transient DataSource cachedDataSource;
	@JsonIgnore
	private transient String cachedQuery;

	public JdbcAdapter() {
	}

	@JsonPropertyDescription("JNDI name of the WebLogic JDBC DataSource, e.g. jdbc/CustomerDS")
	public String getDataSource() { return dataSource; }
	public void setDataSource(String dataSource) { this.dataSource = dataSource; }

	@JsonPropertyDescription("SQL template filename in _templates/ (supports ${var})")
	public String getQueryTemplate() { return queryTemplate; }
	public void setQueryTemplate(String queryTemplate) { this.queryTemplate = queryTemplate; }

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		if (dataSource == null || queryTemplate == null) {
			return CompletableFuture.completedFuture(null);
		}

		final String adapterId = id;
		final Logger sipLogger = SettingsManager.getSipLogger();

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
			sipLogger.warning("JdbcAdapter[" + adapterId + "] template load failed: " + e.getMessage());
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
				sipLogger.warning("JdbcAdapter[" + adapterId + "] query failed: " + e.getMessage());
			}
			return null;
		}, Executors.DB).thenAccept(row -> {
			if (row == null) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer("JdbcAdapter[" + adapterId + "] no rows");
				}
				return;
			}
			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer("JdbcAdapter[" + adapterId + "] row=" + row.keySet());
			}
			runSelectors(ctx, row);
		});
	}
}
