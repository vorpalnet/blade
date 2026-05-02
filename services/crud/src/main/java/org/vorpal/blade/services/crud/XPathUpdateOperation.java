package org.vorpal.blade.services.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.w3c.dom.Document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Sets the text content (or attribute value) of an XML node selected by XPath.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class XPathUpdateOperation implements Operation {
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

	@Override
	public void process(SipServletMessage msg) {
		try {
			String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (xml == null || xml.isEmpty()) return;

			Document doc = XmlHelper.parse(xml);
			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = Configuration.resolveVariables(vars, value);

			if (XmlHelper.setNodeValue(doc, xpath, resolved)) {
				MessageHelper.setAttributeValue(msg, "body", XmlHelper.serialize(doc), contentType);
				SettingsManager.getSipLogger().finer(msg,
						"XPathUpdateOperation - updated " + xpath + "=" + resolved);
			}
		} catch (Exception e) {
			SettingsManager.getSipLogger().logStackTrace(msg, e);
		}
	}

	@JsonPropertyDescription("Optional MIME content type, e.g. application/xml. Null targets the entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("XPath of the node whose value should be updated, e.g. //participant/nameID")
	@FormLayout(wide = true)
	public String getXpath() {
		return xpath;
	}

	public void setXpath(String xpath) {
		this.xpath = xpath;
	}

	@JsonPropertyDescription("New value. Supports ${variable} substitution.")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
