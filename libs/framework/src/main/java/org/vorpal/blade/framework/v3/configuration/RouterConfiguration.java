package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.translations.TranslationTable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Generic routing configuration that combines [Selector]s,
/// [TranslationTable]s, and [Resolver]s into a unified lookup pipeline.
///
/// ## Resolution flow
///
/// When [#findTranslation] is called with an inbound SIP request:
///
/// 1. **Selectors** — each [Selector] in the `selectors` list runs
///    against the SIP message. Every selector's named capturing groups
///    are collected into a session attributes map (and stored on the
///    `SipApplicationSession`). The first selector that returns a
///    non-null key provides the **routing key**.
///
/// 2. **Local plan** — the routing key is looked up in each
///    [TranslationTable] in the `plan` list (in order). Hash tables
///    do exact-match; prefix tables do longest-prefix-match. The first
///    hit returns a [Translation] with the treatment payload.
///
/// 3. **Resolvers** — if no local table matched, each [Resolver] in
///    the `resolvers` list is queried with the routing key and the
///    full session attributes map. Resolvers make external calls
///    (REST APIs, JDBC queries, LDAP lookups) and return a
///    [Translation] dynamically. `${var}` placeholders in URLs,
///    headers, and body templates are populated from session attributes.
///
/// 4. **Default route** — if nothing matched, the `defaultRoute`
///    [Translation] is returned (may be null).
///
/// ## Type parameter
///
/// The type parameter `<T>` is the **treatment type** — the
/// application-specific payload that tells the service what to do with
/// a matched call. For example, the irouter service uses
/// `RoutingTreatment` (a destination URI + custom headers).
///
/// ## Example JSON configuration
///
/// ```json
/// {
///   "selectors": [
///     { "id": "to-user", "attribute": "To",
///       "pattern": "sips?:(?<user>.*)@(?<host>.*)", "expression": "${user}" }
///   ],
///   "plan": [
///     { "type": "hash", "id": "routes", "translations": { ... } },
///     { "type": "prefix", "id": "dial-plan", "translations": { ... } }
///   ],
///   "resolvers": [
///     { "type": "rest", "id": "customer-api",
///       "url": "https://api.example.com/route/${user}", ... }
///   ],
///   "defaultRoute": { "id": "default", "treatment": { ... } }
/// }
/// ```
///
/// @param <T> the treatment type carried by translations
@JsonPropertyOrder({ "selectors", "plan", "resolvers", "defaultRoute" })
public class RouterConfiguration<T> extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonPropertyDescription("Selectors that extract routing keys from SIP messages")
	public List<Selector> selectors = new LinkedList<>();

	@JsonPropertyDescription("Ordered routing plan — local translation tables searched in sequence until a match is found")
	public List<TranslationTable<T>> plan = new LinkedList<>();

	@JsonPropertyDescription("External resolvers queried when no local table matches (REST, JDBC, LDAP)")
	public List<Resolver<T>> resolvers = new LinkedList<>();

	@JsonPropertyDescription("Default translation used when no table or resolver matches")
	public Translation<T> defaultRoute = null;

	public RouterConfiguration() {
	}

	/// Extract a key from the request using the configured selectors.
	/// Tries each selector in order; returns the first match.
	public AttributesKey extractKey(SipServletRequest request) {
		Logger sipLogger = SettingsManager.getSipLogger();
		AttributesKey attrsKey = null;

		for (Selector selector : selectors) {
			attrsKey = selector.findKey(request);
			if (attrsKey != null && attrsKey.key != null) {
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request, "RouterConfiguration.extractKey" +
							" selector=" + selector.getId() +
							", key=" + attrsKey.key);
				}
				break;
			}
		}

		return attrsKey;
	}

	/// Search for a translation matching the request.
	///
	/// Resolution order:
	///   1. Selectors extract a key from the SIP message
	///   2. Local plan (TranslationTables) — searched in sequence
	///   3. External resolvers (REST, JDBC, LDAP) — queried in sequence
	///   4. Default route — fallback if nothing matches
	public Translation<T> findTranslation(SipServletRequest request) {
		Logger sipLogger = SettingsManager.getSipLogger();
		Translation<T> result = null;

		sipLogger.finer(request, "RouterConfiguration.findTranslation - begin...");

		// Run all selectors to collect attributes into the session.
		// The first selector that returns a key provides the routing key.
		Map<String, String> sessionAttributes = new HashMap<>();
		String key = null;

		for (Selector selector : selectors) {
			AttributesKey attrsKey = selector.findKey(request);
			if (attrsKey != null) {
				// Collect all extracted attributes
				if (attrsKey.attributes != null) {
					sessionAttributes.putAll(attrsKey.attributes);
				}
				// First key wins
				if (key == null && attrsKey.key != null) {
					key = attrsKey.key;
					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer(request, "RouterConfiguration.findTranslation" +
								" selector=" + selector.getId() +
								", key=" + key +
								", attributes=" + attrsKey.attributes);
					}
				}
			}
		}

		// Store attributes on the SipApplicationSession for downstream use
		if (!sessionAttributes.isEmpty()) {
			for (Map.Entry<String, String> entry : sessionAttributes.entrySet()) {
				request.getApplicationSession().setAttribute(entry.getKey(), entry.getValue());
			}
		}

		// 1. Try local translation tables
		if (key != null) {
			for (TranslationTable<T> table : plan) {
				result = table.get(key);
				if (result != null) {
					if (sipLogger.isLoggable(Level.FINER)) {
						sipLogger.finer(request, "RouterConfiguration.findTranslation" +
								" table=" + table.getId() +
								", key=" + key +
								", translation=" + result.getId());
					}
					break;
				}
			}
		}

		// 2. Try external resolvers (pass session attributes for ${var} substitution)
		if (result == null && key != null && resolvers != null) {
			for (Resolver<T> resolver : resolvers) {
				try {
					result = resolver.resolve(key, sessionAttributes);
					if (result != null) {
						if (sipLogger.isLoggable(Level.FINER)) {
							sipLogger.finer(request, "RouterConfiguration.findTranslation" +
									" resolver=" + resolver.getId() +
									", key=" + key +
									", translation=" + result.getId());
						}
						break;
					}
				} catch (Exception e) {
					sipLogger.warning(request, "RouterConfiguration.findTranslation" +
							" resolver=" + resolver.getId() +
							" failed: " + e.getMessage());
				}
			}
		}

		// 3. Fall back to default route
		if (result == null) {
			result = defaultRoute;
			if (sipLogger.isLoggable(Level.FINER)) {
				if (result != null) {
					sipLogger.finer(request, "RouterConfiguration.findTranslation" +
							" using defaultRoute id=" + result.getId());
				} else {
					sipLogger.finer(request, "RouterConfiguration.findTranslation" +
							" no match, no defaultRoute.");
				}
			}
		}

		sipLogger.finer(request, "RouterConfiguration.findTranslation - end.");
		return result;
	}

}
