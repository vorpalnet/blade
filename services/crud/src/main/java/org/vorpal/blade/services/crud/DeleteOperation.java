package org.vorpal.blade.services.crud;

import java.io.Serializable;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Removes a header or clears body content from a SIP message.
 * When contentType is specified, removes only the matching MIME part.
 */
@JsonPropertyOrder({ "attribute", "contentType" })
public class DeleteOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String attribute;
	private String contentType;

	public DeleteOperation() {
	}

	public DeleteOperation(String attribute) {
		this.attribute = attribute;
	}

	/**
	 * Removes the specified header or body content from the message.
	 */
	public void process(SipServletMessage msg) {
		try {
			MessageHelper.removeAttribute(msg, attribute, contentType);

			SettingsManager.getSipLogger().finer(msg,
					"DeleteOperation - removed " + attribute);

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("SIP message attribute to delete, e.g. X-Private-Header, body")
	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	@JsonPropertyDescription("Content type to target a specific MIME part for removal")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
}
