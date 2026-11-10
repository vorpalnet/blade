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
	public void check() throws Exception {
		Placeholders.checkXPath(xpath);
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
		if (xml == null || xml.isEmpty()) return;

		Document doc = Placeholders.parseXml(msg, "XPathDeleteOperation", xml);
		if (doc == null) return;
		String path = Placeholders.xpath(xpath, msg);
		if (path == null) {
			Placeholders.skipped(msg, "XPathDeleteOperation", "the XPath", xpath, null);
			return;
		}
		if (XmlHelper.removeNodes(doc, path)) {
			MessageHelper.setAttributeValue(msg, "body", XmlHelper.serialize(xml, doc), contentType);
			SettingsManager.getSipLogger().finer(msg,
					"XPathDeleteOperation - removed " + xpath);
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
