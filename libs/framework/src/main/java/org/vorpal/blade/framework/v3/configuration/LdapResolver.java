package org.vorpal.blade.framework.v3.configuration;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// LDAP-based [Resolver] that queries a directory server (Active
/// Directory, OpenLDAP, Oracle Directory Server, etc.) to resolve
/// a routing key.
///
/// ## Configuration
///
/// ```json
/// {
///   "type": "ldap",
///   "id": "active-directory",
///   "ldapUrl": "ldap://ad.corp.example.com:389",
///   "bindDn": "cn=blade-svc,ou=ServiceAccounts,dc=corp,dc=example,dc=com",
///   "bindPassword": "secret",
///   "searchTemplate": "agent-lookup.ldap",
///   "responseSelector": {
///     "id": "dest-uri",
///     "attribute": "$.destinationURI",
///     "pattern": "(?<uri>.*)",
///     "expression": "${uri}"
///   }
/// }
/// ```
///
/// ## Connection
///
/// The `ldapUrl` is the LDAP server URL (supports `ldap://` and
/// `ldaps://` for TLS). The `bindDn` and `bindPassword` are the
/// service account credentials used to bind to the directory. If
/// both are omitted, an anonymous bind is attempted.
///
/// The LDAP connection is established on first use and cached. If
/// the connection fails on a subsequent call, it is re-established.
///
/// ## Search template
///
/// The search query lives in an external template file in the
/// `_templates/` directory. The template uses the same blank-line
/// separator format as [RestResolver]:
///
/// - **Above the blank line**: search parameters as `key: value` pairs
/// - **Below the blank line**: the LDAP filter string
///
/// Both sections support `${var}` placeholder substitution.
///
/// ### Template format
///
/// ```
/// base: ou=Users,dc=corp,dc=example,dc=com
/// scope: SUBTREE
/// attributes: destinationURI,department,priority,displayName
///
/// (&(objectClass=user)(telephoneNumber=${user}))
/// ```
///
/// ### Search parameters
///
/// | Parameter | Required | Default | Description |
/// |-----------|----------|---------|-------------|
/// | `base`    | yes      | —       | Base DN for the search |
/// | `scope`   | no       | `SUBTREE` | Search scope: `BASE`, `ONE`, or `SUBTREE` |
/// | `attributes` | no    | all     | Comma-separated list of attributes to return |
///
/// ## Response handling
///
/// The **first matching entry** is converted to a JSON object where
/// LDAP attribute names become keys and attribute values become
/// string values:
///
/// ```json
/// {
///   "destinationURI": "sip:agent@queue.example.com",
///   "department": "Support",
///   "priority": "1",
///   "displayName": "Jane Smith"
/// }
/// ```
///
/// This JSON is then processed by the `responseSelector` using
/// [Selector.findKey(JsonNode)] — the same mechanism as [RestResolver]
/// and [JdbcResolver].
///
/// ## Error handling
///
/// - No matching entries returns `null` (no match).
/// - LDAP exceptions propagate to [RouterConfiguration.findTranslation],
///   which logs them as warnings and continues to the next resolver.
/// - The LDAP connection and template are cached after first use.
///
/// @param <T> the treatment type (typically `RoutingTreatment`)
@JsonPropertyOrder({ "id", "description", "ldapUrl", "bindDn", "bindPassword",
		"searchTemplate", "responseSelector" })
public class LdapResolver<T> implements Resolver<T>, Serializable {
	private static final long serialVersionUID = 1L;
	private static final Logger logger = Logger.getLogger(LdapResolver.class.getName());
	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	private String id;
	private String description;

	@JsonPropertyDescription("LDAP server URL, e.g. ldap://ad.corp.example.com:389 or ldaps://... for TLS")
	private String ldapUrl;

	@JsonPropertyDescription("Bind DN (service account), e.g. cn=blade-svc,ou=ServiceAccounts,dc=corp,dc=example,dc=com")
	private String bindDn;

	@JsonPropertyDescription("Bind password for the service account")
	private String bindPassword;

	@JsonPropertyDescription("Search template filename in _templates/ directory (parameters + blank line + LDAP filter)")
	private String searchTemplate;

	@JsonPropertyDescription("Selector that extracts the destination from the LDAP result via JsonPath")
	private Selector responseSelector;

	@JsonIgnore
	private transient DirContext cachedContext;

	@JsonIgnore
	private transient String cachedTemplate;

	/// Default constructor for JSON deserialization.
	public LdapResolver() {
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

	/// Returns the LDAP server URL.
	public String getLdapUrl() {
		return ldapUrl;
	}

	public void setLdapUrl(String ldapUrl) {
		this.ldapUrl = ldapUrl;
	}

	/// Returns the bind DN (service account distinguished name).
	public String getBindDn() {
		return bindDn;
	}

	public void setBindDn(String bindDn) {
		this.bindDn = bindDn;
	}

	/// Returns the bind password.
	public String getBindPassword() {
		return bindPassword;
	}

	public void setBindPassword(String bindPassword) {
		this.bindPassword = bindPassword;
	}

	/// Returns the search template filename.
	public String getSearchTemplate() {
		return searchTemplate;
	}

	public void setSearchTemplate(String searchTemplate) {
		this.searchTemplate = searchTemplate;
	}

	/// Returns the response selector.
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

	/// Resolve the routing key by searching the LDAP directory.
	///
	/// Steps:
	///
	/// 1. Load and cache the search template from `_templates/`
	/// 2. Resolve `${var}` placeholders in both the parameters
	///    and the LDAP filter
	/// 3. Parse search parameters (base, scope, attributes) from
	///    above the blank line
	/// 4. Connect to LDAP (cached, re-established on failure)
	/// 5. Execute the search
	/// 6. Convert the first matching entry to a JSON object
	/// 7. Apply the `responseSelector` or deserialize directly
	@SuppressWarnings("unchecked")
	@Override
	public Translation<T> resolve(String key, Map<String, String> attributes) throws Exception {
		if (ldapUrl == null || searchTemplate == null || key == null) {
			return null;
		}

		// Build substitution map
		Map<String, String> vars = new HashMap<>();
		if (attributes != null) {
			vars.putAll(attributes);
		}
		vars.put("key", key);

		// Load and resolve template
		if (cachedTemplate == null) {
			Path templatePath = Paths.get(TEMPLATES_DIR, searchTemplate);
			if (!Files.exists(templatePath)) {
				throw new IOException("LDAP template not found: " + templatePath);
			}
			cachedTemplate = Files.readString(templatePath);
		}
		String resolved = resolveVars(cachedTemplate, vars);

		// Parse template: parameters above blank line, filter below
		SearchParams params = parseTemplate(resolved);

		if (params.filter == null || params.filter.isEmpty()) {
			logger.warning("LdapResolver[" + id + "]: empty LDAP filter");
			return null;
		}

		if (logger.isLoggable(Level.FINE)) {
			logger.fine("LdapResolver[" + id + "]: searching base=" + params.baseDn +
					", scope=" + params.scope + ", filter=" + params.filter);
		}

		// Connect (cached, re-establish on failure)
		DirContext ctx = getContext();

		// Build search controls
		SearchControls controls = new SearchControls();
		switch (params.scope.toUpperCase()) {
		case "BASE":
			controls.setSearchScope(SearchControls.OBJECT_SCOPE);
			break;
		case "ONE":
		case "ONELEVEL":
			controls.setSearchScope(SearchControls.ONELEVEL_SCOPE);
			break;
		default:
			controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
		}
		if (params.returnAttributes != null) {
			controls.setReturningAttributes(params.returnAttributes);
		}
		controls.setCountLimit(1); // only need first match

		// Execute search
		JsonNode entryJson = null;
		NamingEnumeration<SearchResult> results = ctx.search(params.baseDn, params.filter, controls);
		try {
			if (results.hasMore()) {
				SearchResult result = results.next();
				entryJson = ldapEntryToJson(result.getAttributes());
			}
		} finally {
			results.close();
		}

		if (entryJson == null) {
			if (logger.isLoggable(Level.FINE)) {
				logger.fine("LdapResolver[" + id + "]: no entries found");
			}
			return null;
		}

		if (logger.isLoggable(Level.FINEST)) {
			logger.finest("LdapResolver[" + id + "]: entry: " + entryJson);
		}

		// Extract routing decision
		if (responseSelector != null) {
			AttributesKey attrsKey = responseSelector.findKey(entryJson);
			if (attrsKey != null && attrsKey.key != null) {
				Translation<T> translation = new Translation<>(key);
				translation.setDescription("Resolved via LDAP: " + id);

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
				translation.setDescription("Resolved via LDAP: " + id);
				T treatment = (T) mapper.treeToValue(entryJson, Object.class);
				translation.setTreatment(treatment);
				return translation;
			} catch (Exception e) {
				logger.warning("LdapResolver[" + id + "]: failed to deserialize entry: " + e.getMessage());
			}
		}

		return null;
	}

	// ------------------------------------------------------------------
	// LDAP connection
	// ------------------------------------------------------------------

	/// Get or establish the LDAP connection. Re-establishes if the
	/// cached context is no longer valid.
	private DirContext getContext() throws Exception {
		if (cachedContext != null) {
			try {
				// Quick validity check
				cachedContext.getAttributes("");
				return cachedContext;
			} catch (Exception e) {
				// Connection stale, re-establish
				try {
					cachedContext.close();
				} catch (Exception ignored) {
				}
				cachedContext = null;
			}
		}

		Hashtable<String, String> env = new Hashtable<>();
		env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
		env.put(Context.PROVIDER_URL, ldapUrl);

		if (bindDn != null && bindPassword != null) {
			env.put(Context.SECURITY_AUTHENTICATION, "simple");
			env.put(Context.SECURITY_PRINCIPAL, bindDn);
			env.put(Context.SECURITY_CREDENTIALS, bindPassword);
		}

		// Connection pooling (JDK built-in)
		env.put("com.sun.jndi.ldap.connect.pool", "true");
		env.put("com.sun.jndi.ldap.connect.timeout", "5000");
		env.put("com.sun.jndi.ldap.read.timeout", "5000");

		cachedContext = new InitialDirContext(env);
		return cachedContext;
	}

	// ------------------------------------------------------------------
	// Template parsing
	// ------------------------------------------------------------------

	/// Parsed search template: parameters + filter.
	private static class SearchParams {
		String baseDn = "";
		String scope = "SUBTREE";
		String[] returnAttributes;
		String filter = "";
	}

	/// Parse the resolved template into search parameters and filter.
	/// Parameters are above the blank line (key: value), filter is below.
	private static SearchParams parseTemplate(String resolved) {
		SearchParams params = new SearchParams();

		int blankLine = findBlankLine(resolved);
		if (blankLine >= 0) {
			String headerSection = resolved.substring(0, blankLine).trim();
			params.filter = resolved.substring(blankLine).trim();

			for (String line : headerSection.split("\\r?\\n")) {
				line = line.trim();
				if (line.isEmpty()) continue;
				int colon = line.indexOf(':');
				if (colon > 0) {
					String name = line.substring(0, colon).trim().toLowerCase();
					String value = line.substring(colon + 1).trim();
					switch (name) {
					case "base":
						params.baseDn = value;
						break;
					case "scope":
						params.scope = value;
						break;
					case "attributes":
						params.returnAttributes = value.split("\\s*,\\s*");
						break;
					}
				}
			}
		} else {
			// No blank line — entire content is the filter
			params.filter = resolved.trim();
		}

		return params;
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/// Convert an LDAP entry's attributes to a JSON object.
	/// Multi-valued attributes use the first value.
	private static JsonNode ldapEntryToJson(Attributes attrs) throws Exception {
		ObjectNode node = mapper.createObjectNode();
		NamingEnumeration<? extends Attribute> attrEnum = attrs.getAll();
		while (attrEnum.hasMore()) {
			Attribute attr = attrEnum.next();
			String name = attr.getID();
			Object value = attr.get(); // first value
			if (value != null) {
				node.put(name, value.toString());
			}
		}
		return node;
	}

	/// Find the index of the first blank line.
	private static int findBlankLine(String text) {
		int idx = text.indexOf("\n\n");
		int idx2 = text.indexOf("\r\n\r\n");
		if (idx < 0) return idx2;
		if (idx2 < 0) return idx;
		return Math.min(idx, idx2);
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
