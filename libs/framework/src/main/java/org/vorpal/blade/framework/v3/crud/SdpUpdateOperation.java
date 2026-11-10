package org.vorpal.blade.framework.v3.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;

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
	public void check() throws Exception {
		Placeholders.checkJsonPath(jsonPath);
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
		if (sdp == null || sdp.isEmpty()) return;

		String path = Placeholders.jsonPath(jsonPath, msg);
		if (path == null) {
			Placeholders.skipped(msg, "SdpUpdateOperation", "the path", jsonPath, null);
			return;
		}

		Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
		// No value sets the field to null, as it always has.
		com.fasterxml.jackson.databind.JsonNode resolved = (value == null)
				? com.fasterxml.jackson.databind.node.NullNode.getInstance() : Placeholders.json(value, vars);
		if (resolved == null) {
			Placeholders.skipped(msg, "SdpUpdateOperation", "the value", value, vars);
			return;
		}

		// A node, not a String: SdpHelper parses a String as JSON.
		String result = SdpHelper.updateValue(sdp, path, resolved);
		MessageHelper.setAttributeValue(msg, "body", result, contentType);
		SettingsManager.getSipLogger().finer(msg,
				"SdpUpdateOperation - updated " + jsonPath + "=" + resolved);
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
