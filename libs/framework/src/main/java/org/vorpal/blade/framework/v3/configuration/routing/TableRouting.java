package org.vorpal.blade.framework.v3.configuration.routing;

import java.util.LinkedList;
import java.util.List;

import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// [Routing] that picks a [Route] by consulting an ordered list of
/// [RoutingTable]s — each with its own key expression and match
/// strategy — and returning the first one that produces a match.
///
/// First-match-wins: on [#decide] the routing walks [#getTables] in
/// order, calling [RoutingTable#lookup] against the current Context.
/// The first table that returns a non-null Route wins. If no table
/// matches, the top-level `default` Route is returned (or null, which
/// the servlet treats as a 503 rejection).
///
/// This shape lets operators express fallback-chain routing — "route
/// by specific action, else by dial-plan prefix, else by domain" — as
/// one top-level decision.
///
/// ## Example
///
/// ```json
/// "routing": {
///   "type": "table",
///   "tables": [
///     {
///       "match": "hash",
///       "keyExpression": "${action}",
///       "routes": {
///         "block": { "requestUri": "sip:rejected@pbx.example.com" }
///       }
///     },
///     {
///       "match": "prefix",
///       "keyExpression": "${destNum}",
///       "routes": {
///         "1800": { "requestUri": "sip:tollfree@carrier.example.com" },
///         "1":    { "requestUri": "sip:${destNum}@nanp.carrier.example.com" },
///         "44":   { "requestUri": "sip:${destNum}@uk.carrier.example.com" }
///       }
///     }
///   ],
///   "default": { "requestUri": "sip:${destNum}@intl.carrier.example.com" }
/// }
/// ```
@JsonPropertyOrder({ "type", "tables", "default" })
public class TableRouting extends Routing {
	private static final long serialVersionUID = 1L;

	private List<RoutingTable> tables = new LinkedList<>();
	private Route defaultRoute;

	public TableRouting() {
	}

	@JsonPropertyDescription("Ordered list of routing tables; first lookup to match wins")
	public List<RoutingTable> getTables() {
		return tables;
	}

	public void setTables(List<RoutingTable> tables) {
		this.tables = (tables != null) ? tables : new LinkedList<>();
	}

	/// Convenience for programmatic construction — appends a new
	/// [RoutingTable] and returns it for chaining.
	public RoutingTable addTable(RoutingTable table) {
		if (table != null) tables.add(table);
		return table;
	}

	@JsonProperty("default")
	@JsonPropertyDescription("Fallback Route used when no table matches")
	public Route getDefaultRoute() {
		return defaultRoute;
	}

	@JsonProperty("default")
	public void setDefaultRoute(Route defaultRoute) {
		this.defaultRoute = defaultRoute;
	}

	@Override
	public Route decide(Context ctx) {
		if (ctx == null || tables == null) {
			return defaultRoute;
		}
		for (RoutingTable t : tables) {
			Route hit = t.lookup(ctx);
			if (hit != null) return hit;
		}
		return defaultRoute;
	}
}
