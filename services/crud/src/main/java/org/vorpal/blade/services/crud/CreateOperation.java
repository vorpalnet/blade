package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Adds a new header or body content to a SIP message.
 * Supports ${variable} substitution from SipApplicationSession attributes.
 */
@JsonPropertyOrder({ "attribute", "value", "contentType" })
public class CreateOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String attribute;
	private String value;
	private String contentType;

	public CreateOperation() {
	}

	public CreateOperation(String attribute, String value) {
		this.attribute = attribute;
		this.value = value;
	}

	/**
	 * Resolves variables and sets the header or body content on the message.
	 */
	public void process(SipServletMessage msg) {
		try {
			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = Configuration.resolveVariables(vars, value);

			MessageHelper.setAttributeValue(msg, attribute, resolved, contentType);

			SettingsManager.getSipLogger().finer(msg,
					"CreateOperation - set " + attribute + "=" + resolved);

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("SIP message attribute to create, e.g. X-Custom-Header, body")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Value to set, supports ${variable} substitution from session attributes")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	@JsonPropertyDescription("Content type when creating body content, e.g. application/sdp")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
}
