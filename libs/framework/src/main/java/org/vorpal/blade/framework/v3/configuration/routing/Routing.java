package org.vorpal.blade.framework.v3.configuration.routing;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/// The routing decision strategy for a [org.vorpal.blade.framework.v3.configuration.RouterConfiguration].
///
/// Runs after the pipeline completes (context fully enriched) and returns
/// a concrete [Route] the servlet will proxy to. Subtypes trade off
/// expressiveness for simplicity:
///
/// - [TableRouting] — key-driven lookup with hash, prefix, or range
///   matching and a default fallback. Use for dial-plans, action-based
///   routing, or any decision that's a function of one (possibly
///   compound) context variable.
/// - [ConditionalRouting] — ordered list of `if / else-if / else`
///   clauses, each a boolean expression + a Route. First clause whose
///   expression is true wins. Use when the decision is a function of
///   multiple context variables combined with `&&` / `||` / comparisons.
/// - [DirectRouting] — always the same [Route]. Use when the pipeline's
///   enrichment and templating ( `${destNum}`, `${carrier}`, …) are
///   enough and no lookup is needed.
///
/// Future subtypes (script, multi-table, …) plug in as additional
/// `@JsonSubTypes.Type` entries without touching callers.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = TableRouting.class, name = "table"),
		@JsonSubTypes.Type(value = ConditionalRouting.class, name = "conditional"),
		@JsonSubTypes.Type(value = DirectRouting.class, name = "direct")
})
public abstract class Routing implements Serializable {
	private static final long serialVersionUID = 1L;

	/// Returns the [Route] to proxy to, or null if no decision can be
	/// made. The caller (servlet) is responsible for responding with a
	/// 503 (or similar) when this returns null and there's no other
	/// fallback.
	public abstract Route decide(Context ctx);
}
