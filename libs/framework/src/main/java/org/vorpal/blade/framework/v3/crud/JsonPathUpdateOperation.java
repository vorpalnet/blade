package org.vorpal.blade.framework.v3.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;

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
	public void check() throws Exception {
		Placeholders.checkJsonPath(jsonPath);
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		String json = MessageHelper.getAttributeValue(msg, "body", contentType);
		if (json == null || json.isEmpty()) return;

		String path = Placeholders.jsonPath(jsonPath, msg);
		if (path == null) {
			Placeholders.skipped(msg, "JsonPathUpdateOperation", "the path", jsonPath, null);
			return;
		}

		Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
		// No value sets the field to JSON null, as it always has.
		com.fasterxml.jackson.databind.JsonNode node = (value == null)
				? com.fasterxml.jackson.databind.node.NullNode.getInstance() : Placeholders.json(value, vars);
		if (node == null) {
			Placeholders.skipped(msg, "JsonPathUpdateOperation", "the value", value, vars);
			return;
		}
		String resolved = node.isTextual() ? node.textValue() : node.toString();

		DocumentContext ctx = Placeholders.parseJson(msg, "JsonPathUpdateOperation", json);
		if (ctx == null) return;
		// Nothing at the path is nothing to update: the body is left as it was.
		Object current = ctx.read(path);
		if (current == null || (current instanceof java.util.List && ((java.util.List<?>) current).isEmpty()
				&& com.jayway.jsonpath.JsonPath.compile(path).isDefinite() == false)) {
			SettingsManager.getSipLogger().fine(msg, "JsonPathUpdateOperation - " + jsonPath + " finds nothing; unchanged");
			return;
		}
		// Text goes in as a string, which JsonPath never parses.
		ctx.set(path, Placeholders.plain(node));
		MessageHelper.setAttributeValue(msg, "body", ctx.jsonString(), contentType);

		SettingsManager.getSipLogger().finer(msg,
				"JsonPathUpdateOperation - updated " + jsonPath + "=" + resolved);
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
