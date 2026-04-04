package org.vorpal.blade.services.crud;

import java.io.Serializable;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Removes a property or element from JSON body content using JsonPath.
 */
@JsonPropertyOrder({ "contentType", "jsonPath" })
public class JsonPathDeleteOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String jsonPath;

	public JsonPathDeleteOperation() {
	}

	public JsonPathDeleteOperation(String jsonPath) {
		this.jsonPath = jsonPath;
	}

	/**
	 * Parses the JSON body, deletes the node at the JsonPath location,
	 * and writes the JSON back.
	 */
	public void process(SipServletMessage msg) {
		try {
			String json = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (json == null || json.isEmpty()) {
				return;
			}

			DocumentContext ctx = JsonPath.parse(json);
			ctx.delete(jsonPath);
			String result = ctx.jsonString();

			MessageHelper.setAttributeValue(msg, "body", result, contentType);

			SettingsManager.getSipLogger().finer(msg,
					"JsonPathDeleteOperation - deleted " + jsonPath);

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

	@JsonPropertyDescription("JsonPath expression selecting the node to delete, e.g. $.agent.privateData")
	public String getJsonPath() {
		return jsonPath;
	}

	public void setJsonPath(String jsonPath) {
		this.jsonPath = jsonPath;
	}
}
