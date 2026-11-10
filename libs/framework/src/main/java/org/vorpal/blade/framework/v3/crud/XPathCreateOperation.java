package org.vorpal.blade.framework.v3.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Adds a new XML element or attribute under a parent located by XPath.
/// `elementName` and `attributeName` are mutually exclusive.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class XPathCreateOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String parentXpath;
	private String elementName;
	private String attributeName;
	private String value;

	public XPathCreateOperation() {
	}

	@Override
	public void check() throws Exception {
		Placeholders.checkXPath(parentXpath);
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
		if (xml == null || xml.isEmpty()) return;

		Document doc = Placeholders.parseXml(msg, "XPathCreateOperation", xml);
		if (doc == null) return;
		Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
		String resolved = (value != null) ? Placeholders.value(value, vars) : null;
		if (value != null && resolved == null) {
			Placeholders.skipped(msg, "XPathCreateOperation", "the value", value, vars);
			return;
		}

		String parent = Placeholders.xpath(parentXpath, msg);
		if (parent == null) {
			Placeholders.skipped(msg, "XPathCreateOperation", "the XPath", parentXpath, null);
			return;
		}
		boolean modified = false;
		if (elementName != null) {
			Element created = XmlHelper.createChildElement(doc, parent, elementName, resolved);
			modified = (created != null);
			if (modified) SettingsManager.getSipLogger().finer(msg,
					"XPathCreateOperation - created " + elementName + " under " + parentXpath);
		} else if (attributeName != null) {
			modified = XmlHelper.setElementAttribute(doc, parent, attributeName, resolved);
			if (modified) SettingsManager.getSipLogger().finer(msg,
					"XPathCreateOperation - set @" + attributeName + " on " + parentXpath);
		}

		if (modified) {
			MessageHelper.setAttributeValue(msg, "body", XmlHelper.serialize(xml, doc), contentType);
		}
	}

	@JsonPropertyDescription("Optional MIME content type, e.g. application/xml. Null targets the entire body.")
	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	@JsonPropertyDescription("XPath of the parent under which the new element or attribute is placed.")
	@FormLayout(wide = true)
	public String getParentXpath() {
		return parentXpath;
	}

	public void setParentXpath(String parentXpath) {
		this.parentXpath = parentXpath;
	}

	@JsonPropertyDescription("Name of the child element to create. Mutually exclusive with attributeName.")
	public String getElementName() {
		return elementName;
	}

	public void setElementName(String elementName) {
		this.elementName = elementName;
	}

	@JsonPropertyDescription("Name of the attribute to add to the parent element. Mutually exclusive with elementName.")
	public String getAttributeName() {
		return attributeName;
	}

	public void setAttributeName(String attributeName) {
		this.attributeName = attributeName;
	}

	@JsonPropertyDescription("Value for the new element or attribute. Supports ${variable} substitution.")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
