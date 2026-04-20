package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.FormLayoutGroup;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.MatchStrategy;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;
import org.vorpal.blade.framework.v3.configuration.translations.Translation;
import org.vorpal.blade.framework.v3.configuration.trie.Trie;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Pipeline step that looks up a key in an in-memory translation table and
/// spreads the matched entry's extras into the session [Context].
///
/// On [#invoke]:
///
/// 1. Resolves [#getKeyExpression] against the Context (`${var}`
///    substitution).
/// 2. Looks up the resolved key in [#getTranslations] using the configured
///    [#getMatch] strategy — exact hash (default) or longest-prefix.
/// 3. If a [Translation] is found, every entry in its extras map is
///    written to the Context as a session attribute. So a translation
///    with `customerId="acme"` and `apiKey="xxx"` makes `${customerId}`
///    and `${apiKey}` available to every downstream pipeline step.
///
/// Table connectors are pure enrichment — they never make the routing
/// decision. The routing decision is made by the top-level
/// [org.vorpal.blade.framework.v3.configuration.routing.Routing] after
/// the pipeline completes.
///
/// The selectors list inherited from [Connector] is unused. Hidden from
/// JSON.
@JsonPropertyOrder({ "type", "id", "description", "match", "keyExpression", "translations" })
@FormLayoutGroup({ "id", "match", "keyExpression" })
public class TableConnector extends Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	private MatchStrategy match;
	private String keyExpression;
	private Map<String, Translation> translations = new LinkedHashMap<>();

	@JsonIgnore
	private transient Trie<Translation> prefixIndex;

	public TableConnector() {
	}

	@JsonPropertyDescription("Lookup strategy: hash (exact match, default) or prefix (longest-prefix match)")
	public MatchStrategy getMatch() {
		return match;
	}

	public void setMatch(MatchStrategy match) {
		this.match = match;
		this.prefixIndex = null;
	}

	@JsonPropertyDescription("${var} template producing the lookup key, e.g. ${remoteIP}")
	public String getKeyExpression() {
		return keyExpression;
	}

	public void setKeyExpression(String keyExpression) {
		this.keyExpression = keyExpression;
	}

	@JsonPropertyDescription("Map of lookup key to matched Translation; matched translation's extras spread into the Context")
	public Map<String, Translation> getTranslations() {
		return translations;
	}

	public void setTranslations(Map<String, Translation> translations) {
		this.translations = (translations != null) ? translations : new LinkedHashMap<>();
		this.prefixIndex = null;
	}

	/// Convenience for programmatic construction — creates and registers a
	/// new empty [Translation] under `key`, returning it for chaining.
	public Translation createTranslation(String key) {
		Translation t = new Translation();
		translations.put(key, t);
		this.prefixIndex = null;
		return t;
	}

	/// Selectors are meaningless on a table connector — hide the inherited
	/// list from JSON.
	@Override
	@JsonIgnore
	public List<Selector> getSelectors() {
		return super.getSelectors();
	}

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		if (ctx == null || keyExpression == null) {
			return CompletableFuture.completedFuture(null);
		}

		Logger sipLogger = SettingsManager.getSipLogger();
		try {
			String key = ctx.resolve(keyExpression);
			if (key == null || key.equals(keyExpression)) {
				if (sipLogger != null && sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer("TableConnector[" + id + "] key unresolved from " + keyExpression);
				}
				return CompletableFuture.completedFuture(null);
			}

			Translation match = (this.match == MatchStrategy.prefix)
					? prefixLookup(key)
					: translations.get(key);

			if (match == null) {
				if (sipLogger != null && sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer("TableConnector[" + id + "] no match for key " + key);
				}
				return CompletableFuture.completedFuture(null);
			}

			if (sipLogger != null && sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine("TableConnector[" + id + "] matched key " + key);
			}

			for (Map.Entry<String, String> e : match.getExtras().entrySet()) {
				ctx.put(e.getKey(), e.getValue());
			}
		} catch (Exception e) {
			if (sipLogger != null) {
				sipLogger.warning("TableConnector[" + id + "] failed: " + e.getMessage());
			}
		}
		return CompletableFuture.completedFuture(null);
	}

	private Translation prefixLookup(String key) {
		Trie<Translation> idx = prefixIndex;
		if (idx == null) {
			idx = new Trie<>();
			for (Map.Entry<String, Translation> e : translations.entrySet()) {
				idx.put(e.getKey(), e.getValue());
			}
			prefixIndex = idx;
		}
		return idx.longestPrefixOf(key);
	}
}
