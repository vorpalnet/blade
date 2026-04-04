package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Extracts values from JSON body content using JsonPath expressions
 * and saves them as SipApplicationSession attributes.
 */
@JsonPropertyOrder({ "contentType", "expressions" })
public class JsonPathReadOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private Map<String, String> expressions = new LinkedHashMap<>();

	public JsonPathReadOperation() {
	}

	/**
	 * Parses the JSON body, evaluates each JsonPath expression, and saves
	 * the results as SipApplicationSession attributes.
	 */
	public void process(SipServletMessage msg) {
		try {
			String json = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (json == null || json.isEmpty()) {
				return;
			}

			DocumentContext ctx = JsonPath.parse(json);
			SipApplicationSession appSession = msg.getApplicationSession();

			for (Map.Entry<String, String> entry : expressions.entrySet()) {
				String attrName = entry.getKey();
				String jsonPath = entry.getValue();
				Object result = ctx.read(jsonPath);
				if (result != null) {
					String value = result.toString();
					appSession.setAttribute(attrName, value);
					SettingsManager.getSipLogger().finer(msg,
							"JsonPathReadOperation - saved " + attrName + "=" + value);
				}
			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Content type of the JSON MIME part to target, e.g. application/json. Null reads entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("Map of attribute name to JsonPath expression, e.g. {\"agentId\": \"$.agent.id\"}")
	public Map<String, String> getExpressions() {
		return expressions;
	}

	public void setExpressions(Map<String, String> expressions) {
		this.expressions = expressions;
	}
}
