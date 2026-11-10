package org.vorpal.blade.framework.v3.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Adds a property to an SDP body. If `parentPath` resolves to an array,
/// `value` is appended; if it resolves to an object, `key=value` is set.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SdpCreateOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String parentPath;
	private String key;
	private String value;

	public SdpCreateOperation() {
	}

	@Override
	public void check() throws Exception {
		Placeholders.checkJsonPath(parentPath);
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
		if (sdp == null || sdp.isEmpty()) return;

		String path = Placeholders.jsonPath(parentPath, msg);
		if (path == null) {
			Placeholders.skipped(msg, "SdpCreateOperation", "the path", parentPath, null);
			return;
		}

		Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
		com.fasterxml.jackson.databind.JsonNode resolved = (value != null) ? Placeholders.json(value, vars) : null;
		if (value != null && resolved == null) {
			Placeholders.skipped(msg, "SdpCreateOperation", "the value", value, vars);
			return;
		}

		// A node, not a String: SdpHelper parses a String as JSON.
		String result = SdpHelper.createValue(sdp, path, key, resolved);
		MessageHelper.setAttributeValue(msg, "body", result, contentType);
		SettingsManager.getSipLogger().finer(msg,
				"SdpCreateOperation - added " + key + " at " + parentPath);
	}

	@JsonPropertyDescription("Optional MIME content type, e.g. application/sdp. Null targets the entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("JsonPath of the parent object or array in the SDP-as-JSON.")
	@FormLayout(wide = true)
	public String getParentPath() {
		return parentPath;
	}

	public void setParentPath(String parentPath) {
		this.parentPath = parentPath;
	}

	@JsonPropertyDescription("Property key when parent is an object. Leave null to append to an array parent.")
	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	@JsonPropertyDescription("Value to add. Supports ${variable} substitution.")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
