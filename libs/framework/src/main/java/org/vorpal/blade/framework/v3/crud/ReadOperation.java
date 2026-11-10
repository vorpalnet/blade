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
	public void check() throws Exception {
		if (attribute == null || attribute.isEmpty()) throw new IllegalArgumentException("attribute: required");
		if (pattern == null || pattern.isEmpty()) throw new IllegalArgumentException("pattern: required");
		Placeholders.checkRegex(pattern);
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		Pattern compiled = compiled(msg);
		if (compiled == null) {
			Placeholders.skipped(msg, "ReadOperation", "the pattern for " + attribute, pattern, null);
			return;
		}
		// A header may appear more than once; read the first occurrence the
		// pattern matches, so `tel:` finds the tel identity whichever comes first.
		List<String> candidates = MessageHelper.isPlainHeader(attribute)
				? MessageHelper.getHeaderValues(msg, attribute)
				: java.util.Collections.singletonList(MessageHelper.getAttributeValue(msg, attribute, contentType));
		Matcher matcher = null;
		for (String value : candidates) {
			if (value == null) continue;
			Matcher m = compiled.matcher(value);
			if (m.find()) {
				matcher = m;
				break;
			}
		}
		if (matcher == null) return;

		SipApplicationSession appSession = msg.getApplicationSession();
		for (String name : variableNames()) {
			try {
				String extracted = matcher.group(name);
				if (extracted != null) {
					MessageHelper.putVariable(appSession, name, extracted);
					SettingsManager.getSipLogger().finer(msg,
							"ReadOperation - saved " + name + "=" + extracted);
				}
			} catch (IllegalArgumentException ignore) {
			}
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

	/// The pattern for this message, with any `${name}` filled in from the
	/// session ([Placeholders]); null when one has no value.
	private Pattern compiled(SipServletMessage msg) {
		if (compiledPattern == null) {
			compiledPattern = Placeholders.compileStatic(pattern);
		}
		return Placeholders.compileFor(pattern, compiledPattern, msg);
	}

	@JsonPropertyDescription("SIP attribute to read from: a header name, Request-URI, status, reason, originIP, peerIP, transport, isSecure, or body.")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Regular expression with named groups, e.g. sip:(?<user>[^@]+)@(?<host>[^;>]+). ${name} inserts a session variable, matched literally.")
	@FormLayout(wide = true, regexTest = true)
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
		this.compiledPattern = Placeholders.compileStatic(pattern);
	}

	@JsonPropertyDescription("Optional MIME content type when targeting one part of a multipart body, e.g. application/sdp")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
}
