package org.vorpal.blade.services.crud;

import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Find-and-replace on a SIP attribute. Captures from `pattern` are merged
/// with session variables, then `replacement` is rendered with
/// `${variable}` substitution and written back. Captures from this op stay
/// local to the rule — they are not exported to the session.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "attribute", "contentType", "pattern", "replacement" })
public class UpdateOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private static final Pattern GROUP_NAME_PATTERN = Pattern.compile("\\(\\?<(?<name>[a-zA-Z][a-zA-Z0-9]*)>");

	private String attribute;
	private String pattern;
	private String replacement;
	private String contentType;

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
	public void process(SipServletMessage msg) {
		try {
			String value = MessageHelper.getAttributeValue(msg, attribute, contentType);
			if (value == null) return;

			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());

			LinkedList<String> groupNames = new LinkedList<>();
			Matcher gn = GROUP_NAME_PATTERN.matcher(pattern);
			while (gn.find()) groupNames.add(gn.group("name"));

			Matcher matcher = compiled().matcher(value);
			if (matcher.find()) {
				for (String name : groupNames) {
					try {
						String extracted = matcher.group(name);
						if (extracted != null) vars.put(name, extracted);
					} catch (IllegalArgumentException ignore) {
					}
				}
			}

			String resolved = Context.substitute(replacement, vars);
			MessageHelper.setAttributeValue(msg, attribute, resolved, contentType);
			SettingsManager.getSipLogger().finer(msg,
					"UpdateOperation - updated " + attribute + "=" + resolved);
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	private Pattern compiled() {
		if (compiledPattern == null && pattern != null) {
			compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
		}
		return compiledPattern;
	}

	@JsonPropertyDescription("SIP attribute to update — header name, Request-URI, or body.")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Regex with optional named groups; matches against the current attribute value.")
	@FormLayout(wide = true, regexTest = true)
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = (pattern != null) ? Pattern.compile(pattern, Pattern.DOTALL) : null;
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
}
