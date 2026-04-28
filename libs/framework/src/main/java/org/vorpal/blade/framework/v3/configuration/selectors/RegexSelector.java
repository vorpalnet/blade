package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.FormLayoutGroup;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Reads a source value (the same way [AttributeSelector] does — a
/// SIP header by name, a `Map` payload field, or a special
/// pseudo-header) and runs a regex against it.
///
/// Each **named** capturing group becomes its own session attribute.
/// Numbered groups (`0` = whole match, `1`, `2`, …) are **not** copied
/// to the session (that'd clutter it with cryptic keys) but are
/// available inside this selector's `expression` template —
/// so `${0}`, `${1}`, `${user}`, and `${host}` all work side by
/// side. The rendered expression is stored under this selector's `id`.
///
/// Reserved meta-variables like `${now}` (see [Context]) also resolve
/// inside the `expression` template.
///
/// Use this when extraction requires regex parsing — the canonical
/// case for SIP headers, SDP lines, anything with embedded
/// structure that the source format's own query language can't
/// handle natively. For pure JsonPath/XPath/SDP-field lookups
/// without regex, prefer the dedicated selector.
@JsonPropertyOrder({ "type", "id", "description", "attribute", "pattern", "expression",
		"index", "applicationSession" })
@FormLayoutGroup({ "id", "attribute", "pattern", "expression" })
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
	@FormLayout(regexTest = true)
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

		// Harvest every group the match produced — numbered AND named.
		Map<String, String> groups = extractGroups(m);

		// Only *named* groups go into the session. Numbered keys would
		// collide with real attribute names and surprise later selectors.
		for (Map.Entry<String, String> e : groups.entrySet()) {
			if (isNumericKey(e.getKey())) continue;
			store(ctx, e.getKey(), e.getValue());
		}

		// Expression template sees both kinds — ${0}, ${1}, ${user}, ${host}
		// all work. Session snapshot is folded in so templates can also
		// reference attributes set by upstream selectors/connectors. Reserved
		// meta-variables like ${now} are handled by Context.substitute.
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
		if (sipLogger != null && sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer("RegexSelector[" + id + "] attribute=" + attribute
					+ " key=" + value + " groups=" + groups);
		}
	}

	/// Collects every group the matched `Matcher` produced:
	/// numbered (`"0"` = whole match, `"1"`, `"2"`, …) plus any
	/// `(?<name>…)` named groups discovered by scanning [#pattern].
	/// Null group values (optional groups that didn't match) are
	/// skipped; empty-string matches are preserved — they're legitimate.
	private Map<String, String> extractGroups(Matcher m) {
		Map<String, String> out = new LinkedHashMap<>();

		// Numbered: 0..N
		for (int i = 0; i <= m.groupCount(); i++) {
			String v = m.group(i);
			if (v != null) out.put(String.valueOf(i), v);
		}

		// Named: re-scan the pattern for (?<name>…) tokens and look
		// each up on the same Matcher.
		if (pattern != null) {
			Matcher gm = GROUP_NAME.matcher(pattern);
			while (gm.find()) {
				String n = gm.group("name");
				try {
					String v = m.group(n);
					if (v != null) out.put(n, v);
				} catch (IllegalArgumentException ignore) {
					// pattern had (?<name>…) but the Matcher doesn't know
					// the group — shouldn't happen with a compiled pattern.
				}
			}
		}

		return out;
	}

	private static boolean isNumericKey(String s) {
		if (s == null || s.isEmpty()) return false;
		for (int i = 0; i < s.length(); i++) {
			if (!Character.isDigit(s.charAt(i))) return false;
		}
		return true;
	}
}
