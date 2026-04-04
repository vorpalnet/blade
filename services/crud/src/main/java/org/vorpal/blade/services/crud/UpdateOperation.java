package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Modifies a SIP message attribute using regex pattern matching and replacement.
 * Extracts named groups from the current value, merges with session variables,
 * and applies ${variable} substitution to the replacement template.
 */
@JsonPropertyOrder({ "attribute", "pattern", "replacement", "contentType" })
public class UpdateOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String attribute;
	private String contentType;
	private String replacement;

	@JsonIgnore
	private transient Pattern compiledPattern;
	private String pattern;

	@JsonIgnore
	private static final Pattern GROUP_NAME_PATTERN = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");

	public UpdateOperation() {
	}

	public UpdateOperation(String attribute, String pattern, String replacement) {
		this.attribute = attribute;
		setPattern(pattern);
		this.replacement = replacement;
	}

	/**
	 * Reads the current value, extracts regex groups, merges with session variables,
	 * resolves the replacement template, and writes the result back.
	 */
	public void process(SipServletMessage msg) {
		try {
			String value = MessageHelper.getAttributeValue(msg, attribute, contentType);
			if (value == null) {
				return;
			}

			SipApplicationSession appSession = msg.getApplicationSession();

			// Start with session variables
			Map<String, String> vars = MessageHelper.getSessionVariables(appSession);

			// Extract named groups from the current value and merge
			LinkedList<String> groupNames = new LinkedList<>();
			Matcher groupMatcher = GROUP_NAME_PATTERN.matcher(pattern);
			while (groupMatcher.find()) {
				String name = groupMatcher.group("name");
				if (name != null) {
					groupNames.add(name);
				}
			}

			Matcher matcher = getCompiledPattern().matcher(value);
			if (matcher.find()) {
				for (String name : groupNames) {
					String extracted = matcher.group(name);
					if (extracted != null) {
						vars.put(name, extracted);
					}
				}
			}

			// Resolve ${variables} in the replacement template
			String resolved = Configuration.resolveVariables(vars, replacement);

			// Write back
			MessageHelper.setAttributeValue(msg, attribute, resolved, contentType);

			SettingsManager.getSipLogger().finer(msg,
					"UpdateOperation - updated " + attribute + "=" + resolved);

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("SIP message attribute to update, e.g. From, To, Request-URI, body")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Regex pattern with named capturing groups to match the current value")
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
	}

	@JsonPropertyDescription("Replacement template with ${variable} references")
	public String getReplacement() {
		return replacement;
	}

	public void setReplacement(String replacement) {
		this.replacement = replacement;
	}

	@JsonPropertyDescription("Content type for targeting a specific MIME part, e.g. application/sdp")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	private Pattern getCompiledPattern() {
		if (compiledPattern == null && pattern != null) {
			compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
		}
		return compiledPattern;
	}
}
