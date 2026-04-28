package org.vorpal.blade.framework.v3.configuration;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Boolean matcher used by the FSMAR Transition model: reads a SIP
/// header (or pseudo-field) from the incoming request and returns
/// `true` when its value matches the configured criteria. Unlike
/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector]
/// (which extracts values into the session), this is a yes/no test —
/// it exists to drive FSMAR transition evaluation, not to populate
/// session state.
///
/// Evaluation rules:
/// 1. If [#attribute] is null, match unconditionally.
/// 2. If the header is absent, no match.
/// 3. If [#pattern] is null, header presence is enough — match.
/// 4. If [#pattern] does not match the header value, no match.
/// 5. If [#value] is set, render [#expression] using the regex's named
///    capture groups (syntax `${groupName}`) and match only when the
///    rendered string equals [#value].
/// 6. Otherwise (no [#value]), a successful regex match is enough.
@JsonPropertyOrder({ "id", "attribute", "pattern", "expression", "value" })
public class RequestSelector implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)\\}");

	private String id;
	private String attribute;
	private String pattern;
	private String expression;
	private String value;

	@JsonIgnore
	private transient Pattern compiledPattern;

	public RequestSelector() {
	}

	public RequestSelector(String id, String attribute, String pattern, String expression) {
		this(id, attribute, pattern, expression, null);
	}

	public RequestSelector(String id, String attribute, String pattern, String expression, String value) {
		this.id = id;
		this.attribute = attribute;
		setPattern(pattern);
		this.expression = expression;
		this.value = value;
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

	@JsonPropertyDescription("Optional replacement template, e.g. ${user}@${host}, rendered from the regex's named groups")
	public String getExpression() { return expression; }
	public void setExpression(String expression) { this.expression = expression; }

	@JsonPropertyDescription("Optional literal — when set, the rendered expression must equal this for the selector to match")
	public String getValue() { return value; }
	public void setValue(String value) { this.value = value; }

	public boolean matches(SipServletRequest request) {
		if (attribute == null) return true;
		String headerValue = request.getHeader(attribute);
		if (headerValue == null) return false;
		if (compiledPattern == null) {
			if (pattern == null) return true;
			compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
		}
		Matcher m = compiledPattern.matcher(headerValue);
		if (!m.matches()) return false;
		if (value == null) return true;
		return value.equals(render(expression, m));
	}

	/// Substitutes `${groupName}` references in the template with
	/// values from the supplied Matcher's named groups. A literal
	/// template (no placeholders) is returned unchanged. If a named
	/// group is missing, it is rendered as the empty string.
	private static String render(String template, Matcher m) {
		if (template == null) return "";
		Matcher placeholders = TEMPLATE.matcher(template);
		StringBuffer out = new StringBuffer();
		while (placeholders.find()) {
			String name = placeholders.group(1);
			String replacement;
			try {
				replacement = m.group(name);
			} catch (IllegalArgumentException | IndexOutOfBoundsException e) {
				replacement = "";
			}
			placeholders.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? "" : replacement));
		}
		placeholders.appendTail(out);
		return out.toString();
	}
}
