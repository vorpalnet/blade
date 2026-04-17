package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Reads a source value (the same way [AttributeSelector] does — a
/// SIP header by name, a `Map` payload field, or a special
/// pseudo-header) and runs a regex with named capturing groups
/// against it. Each named group becomes its own session attribute.
/// An optional `expression` template (`${user}@${host}`) builds a
/// final value stored under this selector's `id`.
///
/// Use this when extraction requires regex parsing — the canonical
/// case for SIP headers, SDP lines, anything with embedded
/// structure that the source format's own query language can't
/// handle natively. For pure JsonPath/XPath/SDP-field lookups
/// without regex, prefer the dedicated selector.
@JsonPropertyOrder({ "type", "id", "description", "attribute", "pattern", "expression",
		"index", "applicationSession" })
public class RegexSelector extends Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	@JsonIgnore
	private static final Pattern GROUP_NAME = Pattern.compile("\\<(?<name>[a-zA-Z0-9_]+)\\>");

	protected String pattern;
	protected String expression;

	@JsonIgnore
	protected transient Pattern compiledPattern;

	public RegexSelector() {
	}

	public RegexSelector(String id, String attribute, String pattern, String expression) {
		this.id = id;
		this.attribute = attribute;
		setPattern(pattern);
		this.expression = expression;
	}

	@JsonPropertyDescription("Regex with named capturing groups, e.g. sips?:(?<user>[^@]+)@(?<host>[^;>]+)")
	public String getPattern() { return pattern; }
	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = (pattern != null) ? Pattern.compile(pattern, Pattern.DOTALL) : null;
	}

	@JsonPropertyDescription("Optional replacement template, e.g. ${user}@${host}")
	public String getExpression() { return expression; }
	public void setExpression(String expression) { this.expression = expression; }

	@Override
	public void extract(Context ctx, Object payload) {
		if (attribute == null || pattern == null) return;

		// Source can be a Map/SipServletRequest payload, or a session
		// attribute by name (when chained after another selector).
		String raw = readSource(payload, attribute);
		if (raw == null && ctx != null) {
			raw = ctx.get(attribute);
		}
		if (raw == null) return;

		if (compiledPattern == null) {
			compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
		}

		Matcher m = compiledPattern.matcher(raw);
		if (!m.matches()) return;

		// Named groups → session attributes
		Map<String, String> groups = extractNamedGroups(raw);
		for (Map.Entry<String, String> e : groups.entrySet()) {
			store(ctx, e.getKey(), e.getValue());
		}

		// Final value (under this selector's id) is either the
		// expression template resolved against groups + session, or
		// the raw value.
		String value;
		if (expression != null) {
			Map<String, String> vars = new HashMap<>(ctx.snapshot());
			vars.putAll(groups);
			value = Context.substitute(expression, vars);
		} else {
			value = raw;
		}
		if (value != null) store(ctx, id, value);

		Logger sipLogger = SettingsManager.getSipLogger();
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer("RegexSelector[" + id + "] attribute=" + attribute
					+ " key=" + value + " groups=" + groups);
		}
	}

	private Map<String, String> extractNamedGroups(String raw) {
		Map<String, String> out = new LinkedHashMap<>();
		if (compiledPattern == null || pattern == null) return out;
		LinkedList<String> names = new LinkedList<>();
		Matcher gm = GROUP_NAME.matcher(pattern);
		while (gm.find()) names.add(gm.group("name"));
		Matcher m = compiledPattern.matcher(raw);
		if (m.find()) {
			for (String n : names) {
				try {
					String v = m.group(n);
					if (v != null && !v.isEmpty()) out.put(n, v);
				} catch (IllegalArgumentException ignore) {
					// group name not in the regex (shouldn't happen)
				}
			}
		}
		return out;
	}
}
