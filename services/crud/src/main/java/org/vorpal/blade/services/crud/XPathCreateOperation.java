package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.Map;

import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Creates a new child element or attribute in XML body content.
 * Uses XPath to locate the parent node, then inserts the new content.
 * Supports ${variable} substitution from SipApplicationSession attributes.
 */
@JsonPropertyOrder({ "contentType", "parentXpath", "elementName", "attributeName", "value" })
public class XPathCreateOperation implements Serializable {
	private static final long serialVersionUID = 1L;

	private String contentType;
	private String parentXpath;
	private String elementName;
	private String attributeName;
	private String value;

	public XPathCreateOperation() {
	}

	/**
	 * Parses the XML body, locates the parent node, creates a child element
	 * or attribute, and writes the XML back.
	 * If elementName is set, creates a child element with text content.
	 * If attributeName is set, adds an attribute to the parent element.
	 */
	public void process(SipServletMessage msg) {
		try {
			String xml = MessageHelper.getAttributeValue(msg, "body", contentType);
			if (xml == null || xml.isEmpty()) {
				return;
			}

			Document doc = XmlHelper.parse(xml);
			Map<String, String> vars = MessageHelper.getSessionVariables(msg.getApplicationSession());
			String resolved = (value != null) ? Configuration.resolveVariables(vars, value) : null;

			boolean modified = false;

			if (elementName != null) {
				Element created = XmlHelper.createChildElement(doc, parentXpath, elementName, resolved);
				modified = (created != null);
				if (modified) {
					SettingsManager.getSipLogger().finer(msg,
							"XPathCreateOperation - created element " + elementName + " under " + parentXpath);
				}
			} else if (attributeName != null) {
				modified = XmlHelper.setElementAttribute(doc, parentXpath, attributeName, resolved);
				if (modified) {
					SettingsManager.getSipLogger().finer(msg,
							"XPathCreateOperation - set attribute " + attributeName + " on " + parentXpath);
				}
			}

			if (modified) {
				String result = XmlHelper.serialize(doc);
				MessageHelper.setAttributeValue(msg, "body", result, contentType);
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

	@JsonPropertyDescription("XPath expression selecting the parent node for the new element/attribute")
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

	@JsonPropertyDescription("Value for the new element or attribute, supports ${variable} substitution")
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
