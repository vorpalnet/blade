package org.vorpal.blade.services.crud;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Removes a node from an SDP body via JsonPath against the SDP-as-JSON
/// representation, e.g. `$.media[1]` to remove a whole media stream, or
/// `$.media[0].attributes[?(@.name=='sendonly')]` to drop a single attribute.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SdpDeleteOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String jsonPath;

	public SdpDeleteOperation() {
	}

	public SdpDeleteOperation(String jsonPath) {
		this.jsonPath = jsonPath;
	}

	@Override
	public void process(SipServletMessage msg) {
		try {
			String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (sdp == null || sdp.isEmpty()) return;

			String result = SdpHelper.deleteValue(sdp, jsonPath);
			MessageHelper.setAttributeValue(msg, "body", result, contentType);
			SettingsManager.getSipLogger().finer(msg,
					"SdpDeleteOperation - deleted " + jsonPath);
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

	@JsonPropertyDescription("JsonPath against the SDP-as-JSON selecting the node to delete.")
	@FormLayout(wide = true)
	public String getJsonPath() {
		return jsonPath;
	}

	public void setJsonPath(String jsonPath) {
		this.jsonPath = jsonPath;
	}
}
