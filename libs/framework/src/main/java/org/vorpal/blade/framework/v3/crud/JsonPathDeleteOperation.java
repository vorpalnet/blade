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
	public void check() throws Exception {
		Placeholders.checkJsonPath(jsonPath);
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		String json = MessageHelper.getAttributeValue(msg, "body", contentType);
		if (json == null || json.isEmpty()) return;

		String path = Placeholders.jsonPath(jsonPath, msg);
		if (path == null) {
			Placeholders.skipped(msg, "JsonPathDeleteOperation", "the path", jsonPath, null);
			return;
		}

		DocumentContext ctx = Placeholders.parseJson(msg, "JsonPathDeleteOperation", json);
		if (ctx == null) return;
		ctx.delete(path);
		MessageHelper.setAttributeValue(msg, "body", ctx.jsonString(), contentType);
		SettingsManager.getSipLogger().finer(msg,
				"JsonPathDeleteOperation - deleted " + jsonPath);
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
