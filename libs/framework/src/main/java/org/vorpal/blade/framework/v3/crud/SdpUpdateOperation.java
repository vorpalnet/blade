package org.vorpal.blade.framework.v3.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Updates a value in an SDP body via JsonPath against the SDP-as-JSON
/// representation. Untouched SDP fields are preserved through the round trip.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SdpUpdateOperation implements Operation {
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

	@Override
	public void process(SipServletMessage msg) {
		try {
			String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (sdp == null || sdp.isEmpty()) return;

			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = Context.substitute(value, vars);

			String result = SdpHelper.updateValue(sdp, jsonPath, resolved);
			MessageHelper.setAttributeValue(msg, "body", result, contentType);
			SettingsManager.getSipLogger().finer(msg,
					"SdpUpdateOperation - updated " + jsonPath + "=" + resolved);
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Optional MIME content type, e.g. application/sdp. Null targets the entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("JsonPath against the SDP-as-JSON, e.g. $.connection.address or $.media[0].port")
	@FormLayout(wide = true)
	public String getJsonPath() {
		return jsonPath;
	}

	public void setJsonPath(String jsonPath) {
		this.jsonPath = jsonPath;
	}

	@JsonPropertyDescription("New value. Supports ${variable} substitution.")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
