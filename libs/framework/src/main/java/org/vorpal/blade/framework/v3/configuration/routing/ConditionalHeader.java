package org.vorpal.blade.framework.v3.configuration.routing;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.expressions.Expression;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// An outbound header that's stamped on the INVITE only when its
/// `when` expression evaluates true against the routing
/// [org.vorpal.blade.framework.v3.configuration.Context].
///
/// Used via [Route#getConditionalHeaders]. Unconditional headers stay in
/// the plain `headers` map; this list is for the opt-in case where
/// stamping depends on enriched context values.
///
/// Example:
///
/// ```json
/// "conditionalHeaders": [
///   { "name": "X-Priority", "value": "high",
///     "when": "${customerTier} == premium" }
/// ]
/// ```
@JsonPropertyOrder({ "name", "value", "when" })
public class ConditionalHeader implements Serializable {
	private static final long serialVersionUID = 1L;

	private String name;
	private String value;
	private String when;

	@JsonIgnore
	private transient Expression compiled;

	public ConditionalHeader() {
	}

	public ConditionalHeader(String name, String value, String when) {
		this.name = name;
		this.value = value;
		this.when = when;
	}

	@JsonPropertyDescription("Outbound INVITE header name (e.g. X-Priority)")
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@JsonPropertyDescription("Header value template; supports ${var}")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	@JsonPropertyDescription("Boolean expression gating whether this header is stamped; see Expression grammar")
	public String getWhen() {
		return when;
	}

	public void setWhen(String when) {
		this.when = when;
		this.compiled = null;
	}

	/// Evaluates the `when` expression against `ctx`. Compiles lazily
	/// on first call; parse errors are caught and resolve to false so
	/// a bad expression never accidentally stamps the header.
	public boolean shouldApply(Context ctx) {
		if (when == null || when.isEmpty()) return true;
		try {
			if (compiled == null) compiled = new Expression(when);
			return compiled.evaluate(ctx);
		} catch (Exception e) {
			return false;
		}
	}
}
