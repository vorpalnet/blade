package org.vorpal.blade.framework.v3.configuration.routing;

import java.util.LinkedHashMap;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.MatchStrategy;
import org.vorpal.blade.framework.v3.configuration.trie.Trie;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// [Routing] that picks a [Route] by looking up a resolved key in a map.
///
/// - `keyExpression` is resolved against the session
///   [Context] (`${var}` substitution, composition via `${a}:${b}`).
/// - `match` selects the lookup strategy — exact hash (default) or
///   longest-prefix (dial-plan).
/// - If the resolved key is unset or produces no match, `default` is
///   returned; if `default` is also null, [#decide] returns null and the
///   servlet is expected to respond with a 503 (or similar).
///
/// The prefix trie is built lazily on first prefix-mode call and
/// invalidated whenever `routes` is replaced or `match` flips to
/// PREFIX.
@JsonPropertyOrder({ "type", "match", "keyExpression", "routes", "default" })
public class TableRouting extends Routing {
	private static final long serialVersionUID = 1L;

	private MatchStrategy match;
	private String keyExpression;
	private Map<String, Route> routes = new LinkedHashMap<>();
	private Route defaultRoute;

	@JsonIgnore
	private transient Trie<Route> prefixIndex;

	public TableRouting() {
	}

	@JsonPropertyDescription("Lookup strategy: hash (exact match, default) or prefix (longest-prefix match)")
	public MatchStrategy getMatch() {
		return match;
	}

	public void setMatch(MatchStrategy match) {
		this.match = match;
		this.prefixIndex = null;
	}

	@JsonPropertyDescription("${var} template producing the lookup key for the routing decision, e.g. ${action}")
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

	@JsonProperty("default")
	@JsonPropertyDescription("Fallback Route used when no routes key matches")
	public Route getDefaultRoute() {
		return defaultRoute;
	}

	@JsonProperty("default")
	public void setDefaultRoute(Route defaultRoute) {
		this.defaultRoute = defaultRoute;
	}

	@Override
	public Route decide(Context ctx) {
		if (ctx == null || keyExpression == null) {
			return defaultRoute;
		}
		String key = ctx.resolve(keyExpression);
		if (key == null || key.equals(keyExpression)) {
			return defaultRoute;
		}
		Route hit = (match == MatchStrategy.prefix) ? prefixLookup(key) : routes.get(key);
		return (hit != null) ? hit : defaultRoute;
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
}
