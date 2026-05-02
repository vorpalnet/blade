package org.vorpal.blade.services.crud;

import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
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
	public void process(SipServletMessage msg) {
		try {
			String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (xml == null || xml.isEmpty()) return;

			Document doc = XmlHelper.parse(xml);
			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = (value != null) ? Configuration.resolveVariables(vars, value) : null;

			boolean modified = false;
			if (elementName != null) {
				Element created = XmlHelper.createChildElement(doc, parentXpath, elementName, resolved);
				modified = (created != null);
				if (modified) SettingsManager.getSipLogger().finer(msg,
						"XPathCreateOperation - created " + elementName + " under " + parentXpath);
			} else if (attributeName != null) {
				modified = XmlHelper.setElementAttribute(doc, parentXpath, attributeName, resolved);
				if (modified) SettingsManager.getSipLogger().finer(msg,
						"XPathCreateOperation - set @" + attributeName + " on " + parentXpath);
			}

			if (modified) {
				MessageHelper.setAttributeValue(msg, "body", XmlHelper.serialize(doc), contentType);
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
