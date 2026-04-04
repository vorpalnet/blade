package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.w3c.dom.Document;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Modifies XML body content by setting the value of nodes selected by XPath.
 * Supports ${variable} substitution from SipApplicationSession attributes.
 */
@JsonPropertyOrder({ "contentType", "xpath", "value" })
public class XPathUpdateOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String xpath;
	private String value;

	public XPathUpdateOperation() {
	}

	public XPathUpdateOperation(String xpath, String value) {
		this.xpath = xpath;
		this.value = value;
	}

	/**
	 * Parses the XML body, locates the node via XPath, sets its value
	 * with variable substitution, and writes the XML back.
	 */
	public void process(SipServletMessage msg) {
		try {
			String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (xml == null || xml.isEmpty()) {
				return;
			}

			Document doc = XmlHelper.parse(xml);
			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = Configuration.resolveVariables(vars, value);

			if (XmlHelper.setNodeValue(doc, xpath, resolved)) {
				String result = XmlHelper.serialize(doc);
				MessageHelper.setAttributeValue(msg, "body", result, contentType);
				SettingsManager.getSipLogger().finer(msg,
						"XPathUpdateOperation - updated " + xpath + "=" + resolved);
			}

		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Content type of the XML MIME part to target, e.g. application/xml. Null targets entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("XPath expression selecting the node to update, e.g. //participant/nameID")
	public String getXpath() {
		return xpath;
	}

	public void setXpath(String xpath) {
		this.xpath = xpath;
	}

	@JsonPropertyDescription("New value for the node, supports ${variable} substitution")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
