package org.vorpal.blade.services.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Adds a property at a JsonPath location. If `parentPath` resolves to an
/// object, sets `key=value`; if it resolves to an array, appends `value` and
/// `key` is ignored. Supports `${variable}` substitution from session
/// attributes.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class JsonPathCreateOperation implements Operation {
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

	@Override
	public void process(SipServletMessage msg) {
		try {
			String json = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (json == null || json.isEmpty()) return;

			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = (value != null) ? Configuration.resolveVariables(vars, value) : null;
			Object inserted = (resolved != null) ? MessageHelper.jsonOrString(resolved) : null;

			DocumentContext ctx = JsonPath.parse(json);
			try {
				if (key != null) ctx.put(parentPath, key, inserted);
				else ctx.add(parentPath, inserted);
			} catch (PathNotFoundException e) {
				SettingsManager.getSipLogger().warning(msg,
						"JsonPathCreateOperation - parent path does not exist: " + parentPath);
				return;
			}

			MessageHelper.setAttributeValue(msg, "body", ctx.jsonString(), contentType);
			SettingsManager.getSipLogger().finer(msg,
					"JsonPathCreateOperation - added " + key + "=" + resolved + " at " + parentPath);

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Content type of the JSON MIME part to target, e.g. application/json. Null targets the entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("JsonPath of the parent object or array, e.g. $.agent")
	@FormLayout(wide = true)
	public String getParentPath() {
		return parentPath;
	}

	public void setParentPath(String parentPath) {
		this.parentPath = parentPath;
	}

	@JsonPropertyDescription("Property key to add when the parent is an object. Leave null to append to an array parent.")
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
