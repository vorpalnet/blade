package org.vorpal.blade.framework.v3.configuration.translations;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.FormLayoutGroup;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.MatchStrategy;
import org.vorpal.blade.framework.v3.configuration.RangeKey;
import org.vorpal.blade.framework.v3.configuration.trie.Trie;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One (match, keyExpression, translations) lookup attempt.
///
/// A [org.vorpal.blade.framework.v3.configuration.connectors.TableConnector]
/// holds an ordered list of these and tries each in turn; the first
/// [#lookup] call that returns a non-null [Translation] wins and the
/// TableConnector stops iterating. This lets operators chain
/// "find the customer by IP → else by source number → else by
/// domain" without cascading every match's values into the context.
///
/// The prefix trie is built lazily on first [MatchStrategy#prefix] call
/// and invalidated when `translations` or `match` changes.
@JsonPropertyOrder({ "description", "match", "keyExpression", "translations" })
@FormLayoutGroup({ "match", "keyExpression" })
public class TranslationTable implements Serializable {
	private static final long serialVersionUID = 1L;

	private String description;
	private MatchStrategy match;
	private String keyExpression;
	private Map<String, Translation> translations = new LinkedHashMap<>();

	@JsonIgnore
	private transient Trie<Translation> prefixIndex;

	public TranslationTable() {
	}

	@JsonPropertyDescription("Human-readable description of this lookup attempt")
	@FormLayout(wide = true)
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Lookup strategy: hash (exact match, default), prefix (longest-prefix match), or range (integer-interval match on keys like \"8-17\")")
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

	@JsonPropertyDescription("Map of lookup key to matched Translation")
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

	/// Resolves `keyExpression` against `ctx`, runs hash / prefix / range
	/// lookup, returns the matched [Translation] or null (no match, or
	/// unresolvable key).
	public Translation lookup(Context ctx) {
		if (ctx == null || keyExpression == null) return null;
		String key = ctx.resolve(keyExpression);
		if (key == null || key.equals(keyExpression)) return null;
		if (match == MatchStrategy.prefix) return prefixLookup(key);
		if (match == MatchStrategy.range)  return rangeLookup(key);
		return translations.get(key);
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

	/// Linear scan for the first "lo-hi" range containing the resolved
	/// integer key. Intended for small tables (time-of-day, score
	/// buckets); for larger range tables, swap for an interval tree.
	private Translation rangeLookup(String key) {
		long n;
		try {
			n = Long.parseLong(key.trim());
		} catch (NumberFormatException e) {
			return null;
		}
		for (Map.Entry<String, Translation> e : translations.entrySet()) {
			if (RangeKey.contains(e.getKey(), n)) return e.getValue();
		}
		return null;
	}
}
