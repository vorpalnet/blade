package org.vorpal.blade.framework.v3.crud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Reads values from a JSON body via JsonPath into session attributes.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class JsonPathReadOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private Map<String, String> expressions = new LinkedHashMap<>();

	public JsonPathReadOperation() {
	}

	@Override
	public void process(SipServletMessage msg) {
		try {
			String json = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (json == null || json.isEmpty()) return;

			DocumentContext ctx = JsonPath.parse(json);
			SipApplicationSession appSession = msg.getApplicationSession();

			for (Map.Entry<String, String> entry : expressions.entrySet()) {
				Object result = ctx.read(entry.getValue());
				if (result == null) continue;
				String value = (result instanceof String) ? (String) result : result.toString();
				appSession.setAttribute(entry.getKey(), value);
				SettingsManager.getSipLogger().finer(msg,
						"JsonPathReadOperation - saved " + entry.getKey() + "=" + value);
			}
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@Override
	public List<String> variableNames() {
		return new ArrayList<>(expressions.keySet());
	}

	@JsonPropertyDescription("Optional MIME content type, e.g. application/json. Null reads the entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("Map of variable name to JsonPath expression, e.g. {\"agentId\": \"$.agent.id\"}")
	public Map<String, String> getExpressions() {
		return expressions;
	}

	public void setExpressions(Map<String, String> expressions) {
		this.expressions = expressions;
	}
}
