package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;

import org.vorpal.blade.framework.v3.configuration.expressions.Expression;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// A per-call pass/fail predicate. `when` is an
/// [Expression] evaluated against the call's session variables when the
/// final response arrives — both variables captured by rule `read`
/// operations and the tester's own synthesized ones:
///
/// - `${lastStatus}` — final response status code
/// - `${statusSequence}` — comma-joined status history, e.g. `100,180,200`
/// - `${setupMs}` — milliseconds from INVITE to final response
/// - `${scenario}` — the scenario name
/// - `${index}` — the call index (originated calls)
///
/// Examples: `${lastStatus} == 200` ·
/// `${statusSequence} matches '100,.*200'` · `${setupMs} < 500`
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "id", "when", "onFail" })
public class Assertion implements Serializable {
	private static final long serialVersionUID = 1L;

	public static final String ON_FAIL_FAIL = "fail";
	public static final String ON_FAIL_WARN = "warn";

	private String id;
	private String when;
	private String onFail = ON_FAIL_FAIL;

	private transient Expression compiled;

	public Assertion() {
	}

	public Assertion(String id, String when) {
		this.id = id;
		this.when = when;
	}

	/// The compiled predicate. Lazily compiled and cached; throws
	/// [IllegalArgumentException] if `when` is malformed.
	@JsonIgnore
	public Expression expression() {
		if (compiled == null) {
			compiled = new Expression(when);
		}
		return compiled;
	}

	@JsonPropertyDescription("Identifier reported in metrics and logs.")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Boolean expression over session variables, e.g. ${lastStatus} == 200 && ${setupMs} < 500")
	public String getWhen() {
		return when;
	}

	public void setWhen(String when) {
		this.when = when;
		this.compiled = null;
	}

	@JsonPropertyDescription("What a false result counts as: 'fail' (default) or 'warn'.")
	public String getOnFail() {
		return onFail;
	}

	public void setOnFail(String onFail) {
		this.onFail = onFail;
	}
}
