package org.vorpal.blade.services.crud;

import java.io.Serializable;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Removes a node from SDP body content by converting to JSON,
 * deleting via JsonPath, and converting back to SDP.
 *
 * <p>Example: remove the second media description:
 * <pre>
 * jsonPath: $.media[1]
 * </pre>
 *
 * <p>Example: remove all "sendonly" attributes from first media:
 * <pre>
 * jsonPath: $.media[0].attributes[?(@.name=='sendonly')]
 * </pre>
 */
@JsonPropertyOrder({ "contentType", "jsonPath" })
public class SdpDeleteOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String jsonPath;

	public SdpDeleteOperation() {
	}

	public SdpDeleteOperation(String jsonPath) {
		this.jsonPath = jsonPath;
	}

	/**
	 * Converts SDP to JSON, deletes the node at the JsonPath location,
	 * converts back to SDP, and writes the result to the message.
	 */
	public void process(SipServletMessage msg) {
		try {
			String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (sdp == null || sdp.isEmpty()) {
				return;
			}

			String result = SdpHelper.deleteValue(sdp, jsonPath);
			MessageHelper.setAttributeValue(msg, "body", result, contentType);

			SettingsManager.getSipLogger().finer(msg,
					"SdpDeleteOperation - deleted " + jsonPath);

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

	@JsonPropertyDescription("JsonPath expression on the SDP-as-JSON selecting the node to delete")
	public String getJsonPath() {
		return jsonPath;
	}

	public void setJsonPath(String jsonPath) {
		this.jsonPath = jsonPath;
	}
}
