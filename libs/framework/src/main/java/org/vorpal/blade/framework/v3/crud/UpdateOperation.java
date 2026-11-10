package org.vorpal.blade.framework.v3.crud;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Find-and-replace on a SIP attribute. Captures from `pattern` are merged
/// with session variables, then `replacement` is rendered with
/// `${variable}` substitution and written back. If `pattern` does not match,
/// the attribute is left as it is. By default the replacement becomes the
/// whole new value; with `everyMatch` each match is replaced in place and the
/// text around it kept. A header that appears more than once is updated
/// occurrence by occurrence. Captures from this op stay
/// local to the rule — they are not exported to the session.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "attribute", "contentType", "pattern", "replacement", "everyMatch" })
public class UpdateOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private static final Pattern GROUP_NAME_PATTERN = Pattern.compile("\\(\\?<(?<name>[a-zA-Z][a-zA-Z0-9]*)>");

	private String attribute;
	private String pattern;
	private String replacement;
	private String contentType;
	private boolean everyMatch;

	@JsonIgnore
	private transient Pattern compiledPattern;

	public UpdateOperation() {
	}

	public UpdateOperation(String attribute, String pattern, String replacement) {
		this.attribute = attribute;
		setPattern(pattern);
		this.replacement = replacement;
	}

	@Override
	public void check() throws Exception {
		if (attribute == null || attribute.isEmpty()) throw new IllegalArgumentException("attribute: required");
		if (pattern == null || pattern.isEmpty()) throw new IllegalArgumentException("pattern: required");
		Placeholders.checkRegex(pattern);
		if ((replacement == null || replacement.isEmpty()) && !everyMatch) {
			throw new IllegalArgumentException("replacement: required, since it becomes the whole new value "
					+ "(to delete what the pattern matches, set everyMatch)");
		}
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		Pattern compiled = compiled(msg);
		if (compiled == null) {
			Placeholders.skipped(msg, "UpdateOperation", "the pattern for " + attribute, pattern, null);
			return;
		}
		Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());

		if (MessageHelper.isPlainHeader(attribute)) {
			// Each occurrence on its own: a P-Asserted-Identity with a sip: and a
			// tel: identity keeps both, and only the ones the pattern matches change.
			List<String> before = MessageHelper.getHeaderValues(msg, attribute);
			List<String> after = new java.util.ArrayList<>();
			boolean changed = false;
			for (String value : before) {
				String updated = update(msg, compiled, value, vars);
				if (updated == SKIP) return;
				changed |= (updated != null);
				after.add((updated != null) ? updated : value);
			}
			if (changed) {
				MessageHelper.setHeaderValues(msg, attribute, after);
				SettingsManager.getSipLogger().finer(msg, "UpdateOperation - updated " + attribute + "=" + after);
			}
			return;
		}

		String value = MessageHelper.getAttributeValue(msg, attribute, contentType);
		if (value == null) return;
		String updated = update(msg, compiled, value, vars);
		if (updated == null || updated == SKIP) return;
		MessageHelper.setAttributeValue(msg, attribute, updated, contentType);
		SettingsManager.getSipLogger().finer(msg, "UpdateOperation - updated " + attribute + "=" + updated);
	}

	/// Marks an update abandoned for an unset variable.
	private static final String SKIP = new String("skip");

	/// `value` after the replacement, null when the pattern does not match it
	/// (nothing found, nothing replaced), or [#SKIP] when the replacement
	/// needs a variable that has no value.
	private String update(SipServletMessage msg, Pattern compiled, String value, Map<String, String> vars) {
		// With everyMatch, no replacement deletes each match: the editor drops
		// an empty string, so "" and absent mean the same there. Without it
		// the replacement is the whole new value, and Save requires one.
		String replacement = (this.replacement == null && everyMatch) ? "" : this.replacement;
		Matcher matcher = compiled.matcher(value);
		if (!matcher.find()) {
			SettingsManager.getSipLogger().finer(msg, "UpdateOperation - " + attribute + " does not match; unchanged");
			return null;
		}
		if (!everyMatch) {
			String resolved = Placeholders.value(replacement, captures(matcher, vars));
			if (resolved == null) {
				Placeholders.skipped(msg, "UpdateOperation", "the replacement for " + attribute, replacement, captures(matcher, vars));
				return SKIP;
			}
			return resolved;
		}
		StringBuilder out = new StringBuilder();
		int last = 0;
		do {
			String resolved = Placeholders.value(replacement, captures(matcher, vars));
			if (resolved == null) {
				Placeholders.skipped(msg, "UpdateOperation", "the replacement for " + attribute, replacement, captures(matcher, vars));
				return SKIP;
			}
			out.append(value, last, matcher.start()).append(resolved);
			last = matcher.end();
		} while (matcher.find());
		return out.append(value.substring(last)).toString();
	}

	/// The session variables with this match's named groups laid over them.
	private Map<String, String> captures(Matcher matcher, Map<String, String> vars) {
		Map<String, String> local = new java.util.HashMap<>(vars);
		Matcher gn = GROUP_NAME_PATTERN.matcher(pattern);
		while (gn.find()) {
			try {
				String extracted = matcher.group(gn.group("name"));
				if (extracted != null) local.put(gn.group("name"), extracted);
			} catch (IllegalArgumentException ignore) {
			}
		}
		return local;
	}

	/// The pattern for this message, with any `${name}` filled in from the
	/// session ([Placeholders]); null when one has no value.
	private Pattern compiled(SipServletMessage msg) {
		if (compiledPattern == null) {
			compiledPattern = Placeholders.compileStatic(pattern);
		}
		return Placeholders.compileFor(pattern, compiledPattern, msg);
	}

	@JsonPropertyDescription("SIP attribute to update — header name, Request-URI, or body.")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Regex with optional named groups; matches against the current attribute value. ${name} inserts a session variable, matched literally.")
	@FormLayout(wide = true, regexTest = true)
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = Placeholders.compileStatic(pattern);
	}

	@JsonPropertyDescription("Replacement template; supports ${name} from session variables and from this op's named groups.")
	@FormLayout(wide = true)
	public String getReplacement() {
		return replacement;
	}

	public void setReplacement(String replacement) {
		this.replacement = replacement;
	}

	@JsonPropertyDescription("Optional MIME content type when targeting one part of a multipart body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
	@JsonPropertyDescription("Replace every match in place, keeping the text around it. Off: the replacement becomes the whole new value.")
	public boolean isEveryMatch() {
		return everyMatch;
	}

	public void setEveryMatch(boolean everyMatch) {
		this.everyMatch = everyMatch;
	}
}
