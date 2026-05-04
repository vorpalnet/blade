package org.vorpal.blade.framework.v3.crud;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Removes a property or element from a JSON body via JsonPath.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class JsonPathDeleteOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String jsonPath;

	public JsonPathDeleteOperation() {
	}

	public JsonPathDeleteOperation(String jsonPath) {
		this.jsonPath = jsonPath;
	}

	@Override
	public void process(SipServletMessage msg) {
		try {
			String json = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (json == null || json.isEmpty()) return;

			DocumentContext ctx = JsonPath.parse(json);
			ctx.delete(jsonPath);
			MessageHelper.setAttributeValue(msg, "body", ctx.jsonString(), contentType);
			SettingsManager.getSipLogger().finer(msg,
					"JsonPathDeleteOperation - deleted " + jsonPath);
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

	@JsonPropertyDescription("JsonPath of the node to delete, e.g. $.agent.privateData")
	@FormLayout(wide = true)
	public String getJsonPath() {
		return jsonPath;
	}

	public void setJsonPath(String jsonPath) {
		this.jsonPath = jsonPath;
	}
}
