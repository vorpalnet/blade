package org.vorpal.blade.framework.v3.crud;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Extracts named regex groups from a SIP attribute (header, Request-URI,
/// status, reason, body, …) into [SipApplicationSession] attributes that
/// later operations can reference as `${name}`.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "attribute", "contentType", "pattern" })
public class ReadOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private static final Pattern GROUP_NAME_PATTERN = Pattern.compile("\\(\\?<(?<name>[a-zA-Z][a-zA-Z0-9]*)>");

	private String attribute;
	private String pattern;
	private String contentType;

	@JsonIgnore
	private transient Pattern compiledPattern;

	public ReadOperation() {
	}

	public ReadOperation(String attribute, String pattern) {
		this.attribute = attribute;
		setPattern(pattern);
	}

	@Override
	public void process(SipServletMessage msg) {
		try {
			String value = MessageHelper.getAttributeValue(msg, attribute, contentType);
			if (value == null) return;

			Matcher matcher = compiled().matcher(value);
			if (!matcher.find()) return;

			SipApplicationSession appSession = msg.getApplicationSession();
			for (String name : variableNames()) {
				try {
					String extracted = matcher.group(name);
					if (extracted != null) {
						appSession.setAttribute(name, extracted);
						SettingsManager.getSipLogger().finer(msg,
								"ReadOperation - saved " + name + "=" + extracted);
					}
				} catch (IllegalArgumentException ignore) {
				}
			}
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@Override
	public List<String> variableNames() {
		LinkedList<String> names = new LinkedList<>();
		if (pattern == null) return names;
		Matcher m = GROUP_NAME_PATTERN.matcher(pattern);
		while (m.find()) names.add(m.group("name"));
		return names;
	}

	private Pattern compiled() {
		if (compiledPattern == null && pattern != null) {
			compiledPattern = Pattern.compile(pattern, Pattern.DOTALL);
		}
		return compiledPattern;
	}

	@JsonPropertyDescription("SIP attribute to read from: a header name, Request-URI, status, reason, originIP, peerIP, transport, isSecure, or body.")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Regular expression with named groups, e.g. sip:(?<user>[^@]+)@(?<host>[^;>]+)")
	@FormLayout(wide = true, regexTest = true)
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = (pattern != null) ? Pattern.compile(pattern, Pattern.DOTALL) : null;
	}

	@JsonPropertyDescription("Optional MIME content type when targeting one part of a multipart body, e.g. application/sdp")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
}
