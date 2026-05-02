package org.vorpal.blade.services.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Adds a new header (or replaces a body) with `${variable}` substitution
/// from session attributes.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "attribute", "contentType", "value" })
public class CreateOperation implements Operation {
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

	@Override
	public void process(SipServletMessage msg) {
		try {
			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = Context.substitute(value, vars);

			// `body` + contentType → attach a new MIME part, wrapping into
			// multipart/mixed if needed. Anything else takes the normal path
			// (set header, set body, set Request-URI).
			if (isBody(attribute) && contentType != null) {
				MessageHelper.addBodyPart(msg, contentType, resolved);
				SettingsManager.getSipLogger().finer(msg,
						"CreateOperation - attached " + contentType + " part");
				return;
			}

			MessageHelper.setAttributeValue(msg, attribute, resolved, contentType);
			SettingsManager.getSipLogger().finer(msg,
					"CreateOperation - set " + attribute + "=" + resolved);
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	private static boolean isBody(String attr) {
		return "body".equalsIgnoreCase(attr) || "content".equalsIgnoreCase(attr);
	}

	@JsonPropertyDescription("SIP attribute to create — header name, Request-URI, or body.")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Value to set. Supports ${variable} substitution, e.g. \"${user}@${host}\"")
	@FormLayout(wide = true, multiline = true)
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	@JsonPropertyDescription("Optional MIME content type when creating a specific part of a multipart body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
}
