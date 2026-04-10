package org.vorpal.blade.framework.v3.configuration;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.sql.DataSource;

import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// JDBC-based [Resolver] that queries a database via a WebLogic
/// DataSource to resolve a routing key.
///
/// ## Configuration
///
/// ```json
/// {
///   "type": "jdbc",
///   "id": "customer-db",
///   "dataSource": "jdbc/CustomerDS",
///   "queryTemplate": "customer-route.sql",
///   "responseSelector": {
///     "id": "dest-uri",
///     "attribute": "$.destination_uri",
///     "pattern": "(?<uri>.*)",
///     "expression": "${uri}"
///   }
/// }
/// ```
///
/// ## DataSource
///
/// The `dataSource` field is the JNDI name of a WebLogic JDBC
/// DataSource (e.g. `jdbc/CustomerDS`). No credentials are needed
/// in the BLADE config — WebLogic manages the connection pool,
/// authentication, and failover.
///
/// ## Query template
///
/// The SQL query lives in an external template file in the
/// `_templates/` directory (`<domain>/config/custom/vorpal/_templates/`).
/// All `${var}` placeholders are resolved from session attributes
/// before execution.
///
/// Example template (`_templates/customer-route.sql`):
///
/// ```sql
/// SELECT destination_uri, priority, carrier
/// FROM routing_table
/// WHERE called_number = '${user}'
///   AND region = '${host}'
///   AND active = 1
/// ORDER BY priority
/// FETCH FIRST 1 ROW ONLY
/// ```
///
/// ## Response handling
///
/// The **first row** of the result set is converted to a JSON object
/// where column names become keys and column values become string
/// values:
///
/// ```json
/// {
///   "destination_uri": "sip:agent@queue.example.com",
///   "priority": "1",
///   "carrier": "AT&T"
/// }
/// ```
///
/// This JSON is then processed by the `responseSelector` using
/// [Selector.findKey(JsonNode)] — the same mechanism as [RestResolver].
/// The selector's `attribute` field should be a JsonPath expression
/// (e.g. `$.destination_uri`).
///
/// If no `responseSelector` is configured, the entire first-row JSON
/// is deserialized as the treatment payload.
///
/// ## Error handling
///
/// - Empty result set returns `null` (no match).
/// - SQL exceptions propagate to [RouterConfiguration.findTranslation],
///   which logs them as warnings and continues to the next resolver.
/// - The DataSource JNDI lookup is cached after first use.
/// - The SQL template is cached after first load.
///
/// ## Security note
///
/// The `${var}` substitution is simple string replacement — it does
/// NOT use prepared statement parameters. For production use with
/// untrusted input, consider extending this class to use
/// `PreparedStatement` with positional parameters instead.
///
/// @param <T> the treatment type (typically `RoutingTreatment`)
@JsonPropertyOrder({ "id", "description", "dataSource", "queryTemplate", "responseSelector" })
public class JdbcResolver<T> implements Resolver<T>, Serializable {
	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(JdbcResolver.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	private String id;
	private String description;

	@JsonPropertyDescription("JNDI name of the WebLogic JDBC DataSource, e.g. jdbc/CustomerDS")
	private String dataSource;

	@JsonPropertyDescription("SQL template filename in _templates/ directory (supports ${var} placeholders)")
	private String queryTemplate;

	@JsonPropertyDescription("Selector that extracts the destination from the query result via JsonPath")
	private Selector responseSelector;

	@JsonIgnore
	private transient DataSource cachedDataSource;

	@JsonIgnore
	private transient String cachedQuery;

	/// Default constructor for JSON deserialization.
	public JdbcResolver() {
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public void setId(String id) {
		this.id = id;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public void setDescription(String description) {
		this.description = description;
	}

	/// Returns the JNDI name of the WebLogic JDBC DataSource.
	public String getDataSource() {
		return dataSource;
	}

	public void setDataSource(String dataSource) {
		this.dataSource = dataSource;
	}

	/// Returns the SQL template filename (relative to `_templates/`).
	public String getQueryTemplate() {
		return queryTemplate;
	}

	public void setQueryTemplate(String queryTemplate) {
		this.queryTemplate = queryTemplate;
	}

	/// Returns the [Selector] used to extract routing information
	/// from the query result (converted to JSON).
	public Selector getResponseSelector() {
		return responseSelector;
	}

	public void setResponseSelector(Selector responseSelector) {
		this.responseSelector = responseSelector;
	}

	/// Resolve with no session attributes.
	@Override
	public Translation<T> resolve(String key) throws Exception {
		return resolve(key, null);
	}

	/// Resolve the routing key by querying the configured JDBC DataSource.
	///
	/// Steps:
	///
	/// 1. Load and cache the SQL template from `_templates/`
	/// 2. Resolve `${var}` placeholders from session attributes
	/// 3. Look up the DataSource via JNDI (cached after first lookup)
	/// 4. Execute the query and read the first row
	/// 5. Convert the row to a JSON object (column names → keys)
	/// 6. Apply the `responseSelector` to extract routing info,
	///    or deserialize the entire row as the treatment
	@SuppressWarnings("unchecked")
	@Override
	public Translation<T> resolve(String key, Map<String, String> attributes) throws Exception {
		if (dataSource == null || queryTemplate == null || key == null) {
			return null;
		}

		// Build substitution map
		Map<String, String> vars = new HashMap<>();
		if (attributes != null) {
			vars.putAll(attributes);
		}
		vars.put("key", key);

		// Load and resolve SQL template
		if (cachedQuery == null) {
			Path templatePath = Paths.get(TEMPLATES_DIR, queryTemplate);
			if (!Files.exists(templatePath)) {
				throw new IOException("SQL template not found: " + templatePath);
			}
			cachedQuery = Files.readString(templatePath);
		}
		String resolvedQuery = resolveVars(cachedQuery, vars);

		if (logger.isLoggable(Level.FINE)) {
			logger.fine("JdbcResolver[" + id + "]: executing query");
		}
		if (logger.isLoggable(Level.FINEST)) {
			logger.finest("JdbcResolver[" + id + "]: SQL: " + resolvedQuery);
		}

		// Look up DataSource (cached)
		if (cachedDataSource == null) {
			InitialContext ctx = new InitialContext();
			cachedDataSource = (DataSource) ctx.lookup(dataSource);
		}

		// Execute query and convert first row to JSON
		JsonNode rowJson = null;

		try (Connection conn = cachedDataSource.getConnection();
				Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(resolvedQuery)) {

			if (rs.next()) {
				rowJson = resultSetRowToJson(rs);
			}
		}

		if (rowJson == null) {
			if (logger.isLoggable(Level.FINE)) {
				logger.fine("JdbcResolver[" + id + "]: no rows returned");
			}
			return null;
		}

		if (logger.isLoggable(Level.FINEST)) {
			logger.finest("JdbcResolver[" + id + "]: row: " + rowJson);
		}

		// Extract routing decision
		if (responseSelector != null) {
			AttributesKey attrsKey = responseSelector.findKey(rowJson);
			if (attrsKey != null && attrsKey.key != null) {
				Translation<T> translation = new Translation<>(key);
				translation.setDescription("Resolved via JDBC: " + id);

				Map<String, Object> treatmentMap = new LinkedHashMap<>();
				treatmentMap.put("requestUri", attrsKey.key);
				if (!attrsKey.attributes.isEmpty()) {
					treatmentMap.put("headers", attrsKey.attributes);
				}

				T treatment = (T) mapper.convertValue(treatmentMap, Object.class);
				translation.setTreatment(treatment);
				return translation;
			}
		} else {
			try {
				Translation<T> translation = new Translation<>(key);
				translation.setDescription("Resolved via JDBC: " + id);
				T treatment = (T) mapper.treeToValue(rowJson, Object.class);
				translation.setTreatment(treatment);
				return translation;
			} catch (Exception e) {
				logger.warning("JdbcResolver[" + id + "]: failed to deserialize row: " + e.getMessage());
			}
		}

		return null;
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/// Convert the current row of a [ResultSet] to a JSON object.
	/// Column names become keys; all values are converted to strings.
	private static JsonNode resultSetRowToJson(ResultSet rs) throws Exception {
		ObjectNode node = mapper.createObjectNode();
		ResultSetMetaData meta = rs.getMetaData();
		int columnCount = meta.getColumnCount();

		for (int i = 1; i <= columnCount; i++) {
			String columnName = meta.getColumnLabel(i);
			String value = rs.getString(i);
			if (value != null) {
				node.put(columnName, value);
			}
		}

		return node;
	}

	/// Simple `${var}` placeholder substitution.
	private static String resolveVars(String template, Map<String, String> vars) {
		if (template == null || vars == null || vars.isEmpty()) {
			return template;
		}
		String result = template;
		for (Map.Entry<String, String> entry : vars.entrySet()) {
			result = result.replace("${" + entry.getKey() + "}", entry.getValue());
		}
		return result;
	}
}
