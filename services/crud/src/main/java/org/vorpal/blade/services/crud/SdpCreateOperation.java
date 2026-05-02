package org.vorpal.blade.services.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.Context;

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
	public void process(SipServletMessage msg) {
		try {
			String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (sdp == null || sdp.isEmpty()) return;

			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = (value != null) ? Context.substitute(value, vars) : null;

			String result = SdpHelper.createValue(sdp, parentPath, key, resolved);
			MessageHelper.setAttributeValue(msg, "body", result, contentType);
			SettingsManager.getSipLogger().finer(msg,
					"SdpCreateOperation - added " + key + " at " + parentPath);
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
