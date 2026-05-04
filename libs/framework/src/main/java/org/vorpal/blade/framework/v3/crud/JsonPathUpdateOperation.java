package org.vorpal.blade.framework.v3.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Updates a JSON value at a JsonPath location with `${variable}` substitution.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class JsonPathUpdateOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String jsonPath;
	private String value;

	public JsonPathUpdateOperation() {
	}

	public JsonPathUpdateOperation(String jsonPath, String value) {
		this.jsonPath = jsonPath;
		this.value = value;
	}

	@Override
	public void process(SipServletMessage msg) {
		try {
			String json = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (json == null || json.isEmpty()) return;

			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = Context.substitute(value, vars);

			DocumentContext ctx = JsonPath.parse(json);
			ctx.set(jsonPath, MessageHelper.jsonOrString(resolved));
			MessageHelper.setAttributeValue(msg, "body", ctx.jsonString(), contentType);

			SettingsManager.getSipLogger().finer(msg,
					"JsonPathUpdateOperation - updated " + jsonPath + "=" + resolved);
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Optional MIME content type, e.g. application/json. Null targets the entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("JsonPath of the value to update, e.g. $.agent.name")
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
