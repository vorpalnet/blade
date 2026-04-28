package org.vorpal.blade.framework.v3.configuration.routing;

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

/// One (match, keyExpression, routes) lookup attempt inside a
/// [TableRouting]'s fallback chain.
///
/// Parallels [org.vorpal.blade.framework.v3.configuration.translations.TranslationTable]
/// on the routing side: a TableRouting holds an ordered list of these
/// and tries each in turn. The first [#lookup] call that returns a
/// non-null [Route] wins; remaining tables are skipped.
///
/// The prefix trie is built lazily on first [MatchStrategy#prefix] call
/// and invalidated when `routes` or `match` changes.
@JsonPropertyOrder({ "description", "match", "keyExpression", "routes" })
@FormLayoutGroup({ "match", "keyExpression" })
public class RoutingTable implements Serializable {
	private static final long serialVersionUID = 1L;

	private String description;
	private MatchStrategy match;
	private String keyExpression;
	private Map<String, Route> routes = new LinkedHashMap<>();

	@JsonIgnore
	private transient Trie<Route> prefixIndex;

	public RoutingTable() {
	}

	@JsonPropertyDescription("Human-readable description of this routing table")
	@FormLayout(wide = true)
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("Lookup strategy: hash (exact match, default), prefix (longest-prefix match), or range (integer-interval match)")
	public MatchStrategy getMatch() {
		return match;
	}

	public void setMatch(MatchStrategy match) {
		this.match = match;
		this.prefixIndex = null;
	}

	@JsonPropertyDescription("${var} template producing the lookup key, e.g. ${action} or ${destNum}")
	public String getKeyExpression() {
		return keyExpression;
	}

	public void setKeyExpression(String keyExpression) {
		this.keyExpression = keyExpression;
	}

	@JsonPropertyDescription("Map of route key to Route entry")
	public Map<String, Route> getRoutes() {
		return routes;
	}

	public void setRoutes(Map<String, Route> routes) {
		this.routes = (routes != null) ? routes : new LinkedHashMap<>();
		this.prefixIndex = null;
	}

	/// Resolves `keyExpression` against `ctx`, runs hash / prefix / range
	/// lookup, returns the matched [Route] or null (no match, or
	/// unresolvable key).
	public Route lookup(Context ctx) {
		if (ctx == null || keyExpression == null) return null;
		String key = ctx.resolve(keyExpression);
		if (key == null || key.equals(keyExpression)) return null;
		if (match == MatchStrategy.prefix) return prefixLookup(key);
		if (match == MatchStrategy.range)  return rangeLookup(key);
		return routes.get(key);
	}

	private Route prefixLookup(String key) {
		Trie<Route> idx = prefixIndex;
		if (idx == null) {
			idx = new Trie<>();
			for (Map.Entry<String, Route> e : routes.entrySet()) {
				idx.put(e.getKey(), e.getValue());
			}
			prefixIndex = idx;
		}
		return idx.longestPrefixOf(key);
	}

	private Route rangeLookup(String key) {
		long n;
		try {
			n = Long.parseLong(key.trim());
		} catch (NumberFormatException e) {
			return null;
		}
		for (Map.Entry<String, Route> e : routes.entrySet()) {
			if (RangeKey.contains(e.getKey(), n)) return e.getValue();
		}
		return null;
	}
}
