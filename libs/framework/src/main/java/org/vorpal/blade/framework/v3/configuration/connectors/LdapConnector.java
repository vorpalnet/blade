package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.Executors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// LDAP connector. Searches a directory and passes the first matching
/// entry's attributes as a `Map<String,String>` (LDAP attribute →
/// first value) to each
/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector].
///
/// The `searchTemplate` file uses HTTP-message-like format:
/// parameters above a blank line (`base:`, `scope:`, `attributes:`),
/// LDAP filter below. All `${var}` placeholders are resolved from
/// session state at call time.
///
/// ## Threading
///
/// JNDI LDAP is blocking. [#invoke] runs the search on the
/// framework's bounded [Executors#DB] pool so the SIP container
/// thread is released for the duration of the LDAP round trip.
@JsonPropertyOrder({ "type", "id", "description", "ldapUrl", "bindDn", "bindPassword",
		"searchTemplate", "circuitBreakerCooldownSeconds", "circuitBreakerTrap", "selectors" })
public class LdapConnector extends Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final String TEMPLATES_DIR = "./config/custom/vorpal/_templates/";

	protected String ldapUrl;
	protected String bindDn;
	protected String bindPassword;
	protected String searchTemplate;
	protected Integer circuitBreakerCooldownSeconds;
	protected Boolean circuitBreakerTrap;

	@JsonIgnore
	private transient DirContext cachedContext;
	@JsonIgnore
	private transient String cachedTemplate;
	@JsonIgnore
	private transient CircuitBreaker breaker = new CircuitBreaker();

	public LdapConnector() {
	}

	@JsonPropertyDescription("LDAP server URL, e.g. ldap://ad.corp.example.com:389")
	public String getLdapUrl() { return ldapUrl; }
	public void setLdapUrl(String ldapUrl) { this.ldapUrl = ldapUrl; }

	@JsonPropertyDescription("Bind DN for the service account")
	public String getBindDn() { return bindDn; }
	public void setBindDn(String bindDn) { this.bindDn = bindDn; }

	@JsonPropertyDescription("Bind password")
	public String getBindPassword() { return bindPassword; }
	public void setBindPassword(String bindPassword) { this.bindPassword = bindPassword; }

	@JsonPropertyDescription("Search template filename in _templates/")
	public String getSearchTemplate() { return searchTemplate; }
	public void setSearchTemplate(String searchTemplate) { this.searchTemplate = searchTemplate; }

	@JsonPropertyDescription("Circuit breaker: after a failed search (directory down, bind or connection error), "
			+ "suppress further searches for N seconds and let routing fall to its default route. Prevents hammering "
			+ "a down directory and stops every call from eating the LDAP timeout during an outage. 0 or empty "
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
		if (ldapUrl == null || searchTemplate == null) {
			return CompletableFuture.completedFuture(null);
		}

		final String connectorId = id;
		final Logger sipLogger = SettingsManager.getSipLogger();

		final javax.servlet.sip.SipServletRequest sipReq = ctx.getRequest();

		final boolean breakerEnabled = circuitBreakerCooldownSeconds != null && circuitBreakerCooldownSeconds > 0;
		if (breakerEnabled && breaker.isOpen()) {
			// OPEN — skip the search. Selectors don't run, so the iRouter falls
			// to its default route. No per-call LDAP-timeout wait while the
			// directory is known-down.
			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine(sipReq, tag() + " circuit open — skipping search, routing to default");
			}
			return CompletableFuture.completedFuture(null);
		}

		// Template resolution is cheap — do it on the calling thread.
		final SearchParams params;
		try {
			if (cachedTemplate == null) {
				Path p = Paths.get(TEMPLATES_DIR, searchTemplate);
				if (!Files.exists(p)) throw new IOException("LDAP template not found: " + p);
				cachedTemplate = Files.readString(p);
			}
			params = parseTemplate(ctx.resolve(cachedTemplate));
		} catch (Exception e) {
			sipLogger.warning(sipReq, "LdapConnector[" + connectorId + "] template load failed: " + e.getMessage());
			return CompletableFuture.completedFuture(null);
		}

		if (params.filter == null || params.filter.isEmpty()) {
			sipLogger.warning(sipReq, "LdapConnector[" + connectorId + "] empty filter");
			return CompletableFuture.completedFuture(null);
		}

		return CompletableFuture.supplyAsync(() -> {
			try {
				DirContext dirCtx = getContext();

				SearchControls controls = new SearchControls();
				switch (params.scope.toUpperCase()) {
				case "BASE": controls.setSearchScope(SearchControls.OBJECT_SCOPE); break;
				case "ONE":
				case "ONELEVEL": controls.setSearchScope(SearchControls.ONELEVEL_SCOPE); break;
				default: controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
				}
				if (params.returnAttributes != null) {
					controls.setReturningAttributes(params.returnAttributes);
				}
				controls.setCountLimit(1);

				NamingEnumeration<SearchResult> results = dirCtx.search(params.baseDn, params.filter, controls);
				// The directory was reachable and the search ran — success
				// regardless of whether an entry matched.
				if (breakerEnabled) breaker.recordSuccess(Boolean.TRUE.equals(circuitBreakerTrap), sipReq, tag());
				try {
					if (results.hasMore()) {
						SearchResult sr = results.next();
						Map<String, String> entry = new LinkedHashMap<>();
						Attributes attrs = sr.getAttributes();
						NamingEnumeration<? extends Attribute> attrEnum = attrs.getAll();
						while (attrEnum.hasMore()) {
							Attribute a = attrEnum.next();
							Object v = a.get();
							if (v != null) entry.put(a.getID(), v.toString());
						}
						return entry;
					}
				} finally {
					results.close();
				}
			} catch (Exception e) {
				sipLogger.warning(sipReq, "LdapConnector[" + connectorId + "] search failed: " + e.getMessage());
				if (breakerEnabled) breaker.recordFailure(circuitBreakerCooldownSeconds,
						Boolean.TRUE.equals(circuitBreakerTrap), sipReq, tag(), e.getMessage());
			}
			return null;
		}, Executors.DB).thenAccept(entry -> {
			if (entry == null) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(sipReq, "LdapConnector[" + connectorId + "] no entries");
				}
				return;
			}
			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(sipReq, "LdapConnector[" + connectorId + "] entry=" + entry.keySet());
			}
			runSelectors(ctx, entry);
		});
	}

	private DirContext getContext() throws Exception {
		if (cachedContext != null) {
			try {
				cachedContext.getAttributes("");
				return cachedContext;
			} catch (Exception e) {
				try { cachedContext.close(); } catch (Exception ignore) {}
				cachedContext = null;
			}
		}

		Hashtable<String, String> env = new Hashtable<>();
		env.put(javax.naming.Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
		env.put(javax.naming.Context.PROVIDER_URL, ldapUrl);
		if (bindDn != null && bindPassword != null) {
			env.put(javax.naming.Context.SECURITY_AUTHENTICATION, "simple");
			env.put(javax.naming.Context.SECURITY_PRINCIPAL, bindDn);
			env.put(javax.naming.Context.SECURITY_CREDENTIALS, bindPassword);
		}
		env.put("com.sun.jndi.ldap.connect.pool", "true");
		env.put("com.sun.jndi.ldap.connect.timeout", "5000");
		env.put("com.sun.jndi.ldap.read.timeout", "5000");

		cachedContext = new InitialDirContext(env);
		return cachedContext;
	}

	private static class SearchParams {
		String baseDn = "";
		String scope = "SUBTREE";
		String[] returnAttributes;
		String filter = "";
	}

	private static SearchParams parseTemplate(String resolved) {
		SearchParams params = new SearchParams();
		int blank = findBlankLine(resolved);
		if (blank >= 0) {
			String headerSection = resolved.substring(0, blank).trim();
			params.filter = resolved.substring(blank).trim();
			for (String line : headerSection.split("\\r?\\n")) {
				line = line.trim();
				if (line.isEmpty()) continue;
				int colon = line.indexOf(':');
				if (colon > 0) {
					String name = line.substring(0, colon).trim().toLowerCase();
					String value = line.substring(colon + 1).trim();
					switch (name) {
					case "base": params.baseDn = value; break;
					case "scope": params.scope = value; break;
					case "attributes": params.returnAttributes = value.split("\\s*,\\s*"); break;
					}
				}
			}
		} else {
			params.filter = resolved.trim();
		}
		return params;
	}

	private static int findBlankLine(String text) {
		int idx = text.indexOf("\n\n");
		int idx2 = text.indexOf("\r\n\r\n");
		if (idx < 0) return idx2;
		if (idx2 < 0) return idx;
		return Math.min(idx, idx2);
	}
}
