package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Extracts named regex capturing groups from a SIP message attribute
 * and saves them as SipApplicationSession attributes.
 */
@JsonPropertyOrder({ "attribute", "pattern", "contentType" })
public class ReadOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String attribute;
	private String contentType;

	@JsonIgnore
	private transient Pattern compiledPattern;
	private String pattern;

	@JsonIgnore
	private static final Pattern GROUP_NAME_PATTERN = Pattern.compile("\\<(?<name>[a-zA-Z0-9]+)\\>");

	public ReadOperation() {
	}

	public ReadOperation(String attribute, String pattern) {
		this.attribute = attribute;
		setPattern(pattern);
	}

	/**
	 * Extracts named groups from the message attribute and stores them
	 * in the SipApplicationSession.
	 */
	public void process(SipServletMessage msg) {
		try {
			String value = MessageHelper.getAttributeValue(msg, attribute, contentType);
			if (value == null) {
				return;
			}

			SipApplicationSession appSession = msg.getApplicationSession();

			// Find named groups in the pattern
			LinkedList<String> groupNames = new LinkedList<>();
			Matcher groupMatcher = GROUP_NAME_PATTERN.matcher(pattern);
			while (groupMatcher.find()) {
				String name = groupMatcher.group("name");
				if (name != null) {
					groupNames.add(name);
				}
			}

			// Apply the pattern to the value
			Matcher matcher = getCompiledPattern().matcher(value);
			if (matcher.find()) {
				for (String name : groupNames) {
					String extracted = matcher.group(name);
					if (extracted != null) {
						appSession.setAttribute(name, extracted);
						SettingsManager.getSipLogger().finer(msg,
								"ReadOperation - saved " + name + "=" + extracted);
					}
				}
			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("SIP message attribute to read from, e.g. From, To, Request-URI, body")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Regular expression with named capturing groups, e.g. (?<user>[^@]+)")
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
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
