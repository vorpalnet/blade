package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Adds a new property to JSON body content at a JsonPath location.
 * Supports ${variable} substitution from SipApplicationSession attributes.
 */
@JsonPropertyOrder({ "contentType", "parentPath", "key", "value" })
public class JsonPathCreateOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String parentPath;
	private String key;
	private String value;

	public JsonPathCreateOperation() {
	}

	public JsonPathCreateOperation(String parentPath, String key, String value) {
		this.parentPath = parentPath;
		this.key = key;
		this.value = value;
	}

	/**
	 * Parses the JSON body, adds a new property at the parent path,
	 * and writes the JSON back.
	 */
	public void process(SipServletMessage msg) {
		try {
			String json = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (json == null || json.isEmpty()) {
				return;
			}

			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = (value != null) ? Configuration.resolveVariables(vars, value) : null;

			DocumentContext ctx = JsonPath.parse(json);
			ctx.put(parentPath, key, resolved);
			String result = ctx.jsonString();

			MessageHelper.setAttributeValue(msg, "body", result, contentType);

			SettingsManager.getSipLogger().finer(msg,
					"JsonPathCreateOperation - added " + key + "=" + resolved + " at " + parentPath);

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Content type of the JSON MIME part to target, e.g. application/json. Null targets entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("JsonPath to the parent object where the new property will be added, e.g. $.agent")
	public String getParentPath() {
		return parentPath;
	}

	public void setParentPath(String parentPath) {
		this.parentPath = parentPath;
	}

	@JsonPropertyDescription("Property name to add")
	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	@JsonPropertyDescription("Property value, supports ${variable} substitution")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
