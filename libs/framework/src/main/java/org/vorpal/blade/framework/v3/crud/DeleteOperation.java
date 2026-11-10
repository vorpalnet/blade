package org.vorpal.blade.framework.v3.crud;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Removes a header, or clears (or partially clears) the body.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "attribute", "contentType" })
public class DeleteOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String attribute;
	private String contentType;
	private String pattern;

	public DeleteOperation() {
	}

	public DeleteOperation(String attribute) {
		this.attribute = attribute;
	}

	@Override
	public void check() throws Exception {
		if (attribute == null || attribute.isEmpty()) throw new IllegalArgumentException("attribute: required");
		Placeholders.checkRegex(pattern);
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		if (pattern != null && !pattern.isEmpty()) {
			if (!MessageHelper.isPlainHeader(attribute)) {
				throw new IllegalArgumentException("delete with a pattern removes header occurrences; "
						+ attribute + " is not a header");
			}
			String resolved = Placeholders.regex(pattern, msg);
			if (resolved == null) {
				Placeholders.skipped(msg, "DeleteOperation", "the pattern for " + attribute, pattern, null);
				return;
			}
			java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(resolved, java.util.regex.Pattern.DOTALL);
			java.util.List<String> kept = new java.util.ArrayList<>();
			java.util.List<String> before = MessageHelper.getHeaderValues(msg, attribute);
			for (String value : before) {
				if (!compiled.matcher(value).find()) kept.add(value);
			}
			if (kept.size() != before.size()) {
				MessageHelper.setHeaderValues(msg, attribute, kept);
				SettingsManager.getSipLogger().finer(msg, "DeleteOperation - removed "
						+ (before.size() - kept.size()) + " " + attribute + " matching " + pattern);
			}
			return;
		}
		MessageHelper.removeAttribute(msg, attribute, contentType);
		SettingsManager.getSipLogger().finer(msg,
				"DeleteOperation - removed " + attribute);
	}

	@JsonPropertyDescription("SIP attribute to delete — header name, or `body` to clear the body.")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Optional MIME content type to remove just one part of a multipart body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
	@JsonPropertyDescription("Optional regex: remove only the occurrences of the header it matches. Empty removes every occurrence.")
	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
	}
}
