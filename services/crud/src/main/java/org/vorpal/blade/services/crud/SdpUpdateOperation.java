package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Modifies SDP body content by converting to JSON, setting a value
 * at a JsonPath location, and converting back to SDP.
 * Supports ${variable} substitution from SipApplicationSession attributes.
 *
 * <p>Example: change first media line from sendrecv to inactive:
 * <pre>
 * jsonPath: $.media[0].attributes[?(@.name=='sendrecv')].name
 * value: inactive
 * </pre>
 */
@JsonPropertyOrder({ "contentType", "jsonPath", "value" })
public class SdpUpdateOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String jsonPath;
	private String value;

	public SdpUpdateOperation() {
	}

	public SdpUpdateOperation(String jsonPath, String value) {
		this.jsonPath = jsonPath;
		this.value = value;
	}

	/**
	 * Converts SDP to JSON, sets the value at the JsonPath location,
	 * converts back to SDP, and writes the result to the message.
	 */
	public void process(SipServletMessage msg) {
		try {
			String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (sdp == null || sdp.isEmpty()) {
				return;
			}

			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = Configuration.resolveVariables(vars, value);

			String result = SdpHelper.updateValue(sdp, jsonPath, resolved);
			MessageHelper.setAttributeValue(msg, "body", result, contentType);

			SettingsManager.getSipLogger().finer(msg,
					"SdpUpdateOperation - updated " + jsonPath + "=" + resolved);

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Content type for targeting a specific MIME part, e.g. application/sdp. Null targets entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("JsonPath expression on the SDP-as-JSON selecting the value to update")
	public String getJsonPath() {
		return jsonPath;
	}

	public void setJsonPath(String jsonPath) {
		this.jsonPath = jsonPath;
	}

	@JsonPropertyDescription("New value, supports ${variable} substitution")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
