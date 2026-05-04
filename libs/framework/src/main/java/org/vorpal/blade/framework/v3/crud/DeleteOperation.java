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

	public DeleteOperation() {
	}

	public DeleteOperation(String attribute) {
		this.attribute = attribute;
	}

	@Override
	public void process(SipServletMessage msg) {
		try {
			MessageHelper.removeAttribute(msg, attribute, contentType);
			SettingsManager.getSipLogger().finer(msg,
					"DeleteOperation - removed " + attribute);
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
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
}
