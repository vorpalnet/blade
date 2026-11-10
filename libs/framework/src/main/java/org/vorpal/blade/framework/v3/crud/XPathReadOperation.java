package org.vorpal.blade.framework.v3.crud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.w3c.dom.Document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Reads values from an XML body via XPath into session attributes. The map
/// key is the attribute name; the value is the XPath expression.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class XPathReadOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private Map<String, String> expressions = new LinkedHashMap<>();

	public XPathReadOperation() {
	}

	@Override
	public void check() throws Exception {
		Placeholders.checkEach(expressions, Placeholders::checkXPath);
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
		if (xml == null || xml.isEmpty()) return;

		Document doc = Placeholders.parseXml(msg, "XPathReadOperation", xml);
		if (doc == null) return;
		SipApplicationSession appSession = msg.getApplicationSession();

		for (Map.Entry<String, String> entry : expressions.entrySet()) {
			String path = Placeholders.xpath(entry.getValue(), msg);
			if (path == null) {
				Placeholders.skipped(msg, "XPathReadOperation", entry.getKey() + "'s XPath", entry.getValue(), null);
				continue;
			}
			String value = XmlHelper.evaluateString(doc, path);
			if (value != null && !value.isEmpty()) {
				MessageHelper.putVariable(appSession, entry.getKey(), value);
				SettingsManager.getSipLogger().finer(msg,
						"XPathReadOperation - saved " + entry.getKey() + "=" + value);
			}
		}
	}

	@Override
	public List<String> variableNames() {
		return new ArrayList<>(expressions.keySet());
	}

	@JsonPropertyDescription("Optional MIME content type, e.g. application/xml. Null reads the entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("Map of variable name to XPath expression, e.g. {\"sessionId\": \"//recording/@session-id\"}")
	public Map<String, String> getExpressions() {
		return expressions;
	}

	public void setExpressions(Map<String, String> expressions) {
		this.expressions = expressions;
	}
}
