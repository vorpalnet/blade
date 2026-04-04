package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.w3c.dom.Document;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Extracts values from XML body content using XPath expressions
 * and saves them as SipApplicationSession attributes.
 */
@JsonPropertyOrder({ "contentType", "expressions" })
public class XPathReadOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private Map<String, String> expressions = new LinkedHashMap<>();

	public XPathReadOperation() {
	}

	/**
	 * Parses the XML body, evaluates each XPath expression, and saves
	 * the results as SipApplicationSession attributes.
	 */
	public void process(SipServletMessage msg) {
		try {
			String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (xml == null || xml.isEmpty()) {
				return;
			}

			Document doc = XmlHelper.parse(xml);
			SipApplicationSession appSession = msg.getApplicationSession();

			for (Map.Entry<String, String> entry : expressions.entrySet()) {
				String attrName = entry.getKey();
				String xpath = entry.getValue();
				String value = XmlHelper.evaluateString(doc, xpath);
				if (value != null && !value.isEmpty()) {
					appSession.setAttribute(attrName, value);
					SettingsManager.getSipLogger().finer(msg,
							"XPathReadOperation - saved " + attrName + "=" + value);
				}
			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Content type of the XML MIME part to target, e.g. application/xml. Null reads entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("Map of attribute name to XPath expression, e.g. {\"sessionId\": \"//recording/@session-id\"}")
	public Map<String, String> getExpressions() {
		return expressions;
	}

	public void setExpressions(Map<String, String> expressions) {
		this.expressions = expressions;
	}
}
