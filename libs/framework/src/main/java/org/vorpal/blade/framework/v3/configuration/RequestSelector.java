package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Boolean matcher used by the FSMAR Transition model: reads a SIP
/// header (or pseudo-field) from the incoming request and returns
/// `true` when its value matches [#pattern]. Unlike
/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector]
/// (which extracts values into the session), this is a yes/no test —
/// it exists to drive FSMAR transition evaluation, not to populate
/// session state.
@JsonPropertyOrder({ "id", "attribute", "pattern", "expression" })
public class RequestSelector implements Serializable {
	private static final long serialVersionUID = 1L;

	private String id;
	private String attribute;
	private String pattern;
	private String expression;

	@JsonIgnore
	private transient Pattern compiledPattern;

	public RequestSelector() {
	}

	public RequestSelector(String id, String attribute, String pattern, String expression) {
		this.id = id;
		this.attribute = attribute;
		setPattern(pattern);
		this.expression = expression;
	}

	@JsonPropertyDescription("Identifier for logging and diagnostics")
	public String getId() { return id; }
	public void setId(String id) { this.id = id; }

	@JsonPropertyDescription("SIP header name (or pseudo-field) whose value is tested")
	public String getAttribute() { return attribute; }
	public void setAttribute(String attribute) { this.attribute = attribute; }

	@JsonPropertyDescription("Regex with optional named capturing groups")
	public String getPattern() { return pattern; }
	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = (pattern != null) ? Pattern.compile(pattern, Pattern.DOTALL) : null;
	}

	@JsonPropertyDescription("Optional replacement template, e.g. ${user}@${host}")
	public String getExpression() { return expression; }
	public void setExpression(String expression) { this.expression = expression; }

	/// Returns true if [#attribute] is present on the request and its
	/// value matches [#pattern]. A null/empty pattern matches iff the
	/// header is present at all.
	public boolean matches(SipServletRequest request) {
		if (attribute == null) return true;
		String value = request.getHeader(attribute);
		if (value == null) return false;
		if (compiledPattern == null) {
			if (pattern == null) return true;
			compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
		}
		return compiledPattern.matcher(value).matches();
	}
}
