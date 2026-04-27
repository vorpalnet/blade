package org.vorpal.blade.framework.v3.configuration.routing;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.expressions.Expression;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// [Routing] that picks a [Route] by evaluating an ordered list of
/// `if / else-if / else` clauses against the session [Context].
///
/// Each [Clause] pairs a boolean expression with a [Route]. [#decide]
/// walks the clause list in order and returns the Route from the first
/// clause whose expression evaluates to true. If none match, the
/// `default` Route is returned (or null, which the servlet treats as a
/// 503 rejection).
///
/// Expression grammar is documented on
/// [org.vorpal.blade.framework.v3.configuration.expressions.Expression].
/// Typical shapes:
///
/// ```json
/// "routing": {
///   "type": "conditional",
///   "clauses": [
///     { "when": "${action} == block",
///       "route": { "requestUri": "sip:rejected@pbx.example.com" } },
///     { "when": "${action} == allow && ${shift} == business",
///       "route": { "requestUri": "${routeTo}" } }
///   ],
///   "default": { "requestUri": "sip:operator@pbx.example.com" }
/// }
/// ```
@JsonPropertyOrder({ "type", "clauses", "default" })
public class ConditionalRouting extends Routing {
	private static final long serialVersionUID = 1L;

	private List<Clause> clauses = new ArrayList<>();
	private Route defaultRoute;

	@JsonPropertyDescription("Ordered if/elif clauses; first one whose `when` expression is true wins")
	public List<Clause> getClauses() {
		return clauses;
	}

	public void setClauses(List<Clause> clauses) {
		this.clauses = (clauses != null) ? clauses : new ArrayList<>();
	}

	@JsonProperty("default")
	@JsonPropertyDescription("Fallback Route when no clause matches")
	public Route getDefaultRoute() {
		return defaultRoute;
	}

	@JsonProperty("default")
	public void setDefaultRoute(Route defaultRoute) {
		this.defaultRoute = defaultRoute;
	}

	/// Fluent helper for programmatic construction — appends a new clause
	/// and returns it for field population.
	public Clause addClause(String when, Route route) {
		Clause c = new Clause();
		c.setWhen(when);
		c.setRoute(route);
		clauses.add(c);
		return c;
	}

	@Override
	public Route decide(Context ctx) {
		if (clauses != null) {
			for (Clause c : clauses) {
				if (c.matches(ctx)) return c.getRoute();
			}
		}
		return defaultRoute;
	}

	/// One if-clause: a boolean expression plus the Route to return when
	/// the expression is true.
	@JsonPropertyOrder({ "when", "route" })
	public static class Clause implements Serializable {
		private static final long serialVersionUID = 1L;

		private String when;
		private Route route;

		@JsonIgnore
		private transient Expression compiled;

		public Clause() {
		}

		@JsonPropertyDescription("Boolean expression; see Expression grammar (e.g. \"${action} == allow && ${score} > 80\")")
		public String getWhen() {
			return when;
		}

		public void setWhen(String when) {
			this.when = when;
			this.compiled = null;
		}

		@JsonPropertyDescription("Route to return when the `when` expression evaluates true")
		public Route getRoute() {
			return route;
		}

		public void setRoute(Route route) {
			this.route = route;
		}

		/// Evaluates the `when` expression against `ctx`. Compiles lazily
		/// on first call; parse errors are caught and logged-via-false so
		/// a bad clause can't blow up the whole routing decision.
		public boolean matches(Context ctx) {
			if (when == null || when.isEmpty()) return true;
			try {
				if (compiled == null) compiled = new Expression(when);
				return compiled.evaluate(ctx);
			} catch (Exception e) {
				return false;
			}
		}
	}
}
