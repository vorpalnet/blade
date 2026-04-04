package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Adds a property to SDP body content by converting to JSON,
 * inserting via JsonPath, and converting back to SDP.
 * Supports ${variable} substitution from SipApplicationSession attributes.
 *
 * <p>Example: add an attribute to the first media description:
 * <pre>
 * parentPath: $.media[0].attributes
 * key: (not used for array append)
 * value: {"name":"label","value":"12345"}
 * </pre>
 */
@JsonPropertyOrder({ "contentType", "parentPath", "key", "value" })
public class SdpCreateOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String parentPath;
	private String key;
	private String value;

	public SdpCreateOperation() {
	}

	/**
	 * Converts SDP to JSON, adds a property at the parent path,
	 * converts back to SDP, and writes the result to the message.
	 */
	public void process(SipServletMessage msg) {
		try {
			String sdp = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (sdp == null || sdp.isEmpty()) {
				return;
			}

			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = (value != null) ? Configuration.resolveVariables(vars, value) : null;

			String result = SdpHelper.createValue(sdp, parentPath, key, resolved);
			MessageHelper.setAttributeValue(msg, "body", result, contentType);

			SettingsManager.getSipLogger().finer(msg,
					"SdpCreateOperation - added " + key + " at " + parentPath);

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

	@JsonPropertyDescription("JsonPath to the parent object/array in the SDP-as-JSON")
	public String getParentPath() {
		return parentPath;
	}

	public void setParentPath(String parentPath) {
		this.parentPath = parentPath;
	}

	@JsonPropertyDescription("Property key to add (for objects)")
	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	@JsonPropertyDescription("Value to add, supports ${variable} substitution")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
