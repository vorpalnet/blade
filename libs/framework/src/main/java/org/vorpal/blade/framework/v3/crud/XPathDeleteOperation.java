package org.vorpal.blade.framework.v3.crud;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.w3c.dom.Document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Removes XML nodes selected by XPath.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class XPathDeleteOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String xpath;

	public XPathDeleteOperation() {
	}

	public XPathDeleteOperation(String xpath) {
		this.xpath = xpath;
	}

	@Override
	public void process(SipServletMessage msg) {
		try {
			String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (xml == null || xml.isEmpty()) return;

			Document doc = XmlHelper.parse(xml);
			if (XmlHelper.removeNodes(doc, xpath)) {
				MessageHelper.setAttributeValue(msg, "body", XmlHelper.serialize(doc), contentType);
				SettingsManager.getSipLogger().finer(msg,
						"XPathDeleteOperation - removed " + xpath);
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

	@JsonPropertyDescription("XPath selecting nodes to remove, e.g. //extension[@name='private']")
	@FormLayout(wide = true)
	public String getXpath() {
		return xpath;
	}

	public void setXpath(String xpath) {
		this.xpath = xpath;
	}
}
