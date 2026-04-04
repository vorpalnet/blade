package org.vorpal.blade.services.crud;

import java.io.Serializable;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.w3c.dom.Document;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Removes nodes from XML body content using XPath selection.
 */
@JsonPropertyOrder({ "contentType", "xpath" })
public class XPathDeleteOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String xpath;

	public XPathDeleteOperation() {
	}

	public XPathDeleteOperation(String xpath) {
		this.xpath = xpath;
	}

	/**
	 * Parses the XML body, removes all nodes matching the XPath expression,
	 * and writes the XML back.
	 */
	public void process(SipServletMessage msg) {
		try {
			String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (xml == null || xml.isEmpty()) {
				return;
			}

			Document doc = XmlHelper.parse(xml);

			if (XmlHelper.removeNodes(doc, xpath)) {
				String result = XmlHelper.serialize(doc);
				MessageHelper.setAttributeValue(msg, "body", result, contentType);
				SettingsManager.getSipLogger().finer(msg,
						"XPathDeleteOperation - removed nodes matching " + xpath);
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

	@JsonPropertyDescription("XPath expression selecting nodes to remove, e.g. //extension[@name='private']")
	public String getXpath() {
		return xpath;
	}

	public void setXpath(String xpath) {
		this.xpath = xpath;
	}
}
