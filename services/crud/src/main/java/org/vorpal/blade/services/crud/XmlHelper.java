package org.vorpal.blade.services.crud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Utility for XML parsing, XPath evaluation, and DOM serialization.
 * All methods are static and thread-safe (factories are created per-call).
 */
public class XmlHelper implements Serializable {
	private static final long serialVersionUID = 1L;

	private XmlHelper() {
	}

	/**
	 * Parses an XML string into a DOM Document.
	 */
	public static Document parse(String xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * Serializes a DOM Document back to an XML string.
	 * Omits the XML declaration to keep SIP body clean.
	 */
	public static String serialize(Document doc) throws Exception {
		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer transformer = tf.newTransformer();
		transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		transformer.setOutputProperty(OutputKeys.INDENT, "no");
		StringWriter writer = new StringWriter();
		transformer.transform(new DOMSource(doc), new StreamResult(writer));
		return writer.toString();
	}

	/**
	 * Evaluates an XPath expression and returns the text content of the first
	 * matching node, or the node value for attributes.
	 */
	public static String evaluateString(Document doc, String xpathExpr) throws Exception {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = xpath.compile(xpathExpr);
		return (String) expr.evaluate(doc, XPathConstants.STRING);
	}

	/**
	 * Evaluates an XPath expression and returns all matching nodes.
	 */
	public static NodeList evaluateNodeList(Document doc, String xpathExpr) throws Exception {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = xpath.compile(xpathExpr);
		return (NodeList) expr.evaluate(doc, XPathConstants.NODESET);
	}

	/**
	 * Evaluates an XPath expression and returns the first matching node.
	 */
	public static Node evaluateNode(Document doc, String xpathExpr) throws Exception {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = xpath.compile(xpathExpr);
		return (Node) expr.evaluate(doc, XPathConstants.NODE);
	}

	/**
	 * Sets the text content of the first node matching the XPath expression.
	 * For attribute nodes, sets the attribute value.
	 */
	public static boolean setNodeValue(Document doc, String xpathExpr, String value) throws Exception {
		Node node = evaluateNode(doc, xpathExpr);
		if (node == null) {
			return false;
		}
		switch (node.getNodeType()) {
		case Node.ATTRIBUTE_NODE:
			node.setNodeValue(value);
			return true;
		case Node.ELEMENT_NODE:
			node.setTextContent(value);
			return true;
		case Node.TEXT_NODE:
			node.setNodeValue(value);
			return true;
		default:
			node.setTextContent(value);
			return true;
		}
	}

	/**
	 * Removes all nodes matching the XPath expression from the document.
	 */
	public static boolean removeNodes(Document doc, String xpathExpr) throws Exception {
		NodeList nodes = evaluateNodeList(doc, xpathExpr);
		if (nodes.getLength() == 0) {
			return false;
		}
		for (int i = nodes.getLength() - 1; i >= 0; i--) {
			Node node = nodes.item(i);
			if (node.getNodeType() == Node.ATTRIBUTE_NODE) {
				((Element) ((org.w3c.dom.Attr) node).getOwnerElement())
						.removeAttributeNode((org.w3c.dom.Attr) node);
			} else if (node.getParentNode() != null) {
				node.getParentNode().removeChild(node);
			}
		}
		return true;
	}

	/**
	 * Creates a child element under the first node matching parentXpath.
	 * Returns the created element, or null if parent not found.
	 */
	public static Element createChildElement(Document doc, String parentXpath, String elementName, String textContent)
			throws Exception {
		Node parent = evaluateNode(doc, parentXpath);
		if (parent == null) {
			return null;
		}
		Element child = doc.createElement(elementName);
		if (textContent != null) {
			child.setTextContent(textContent);
		}
		parent.appendChild(child);
		return child;
	}

	/**
	 * Sets an attribute on the first element matching the XPath expression.
	 */
	public static boolean setElementAttribute(Document doc, String elementXpath, String attrName, String attrValue)
			throws Exception {
		Node node = evaluateNode(doc, elementXpath);
		if (node == null || node.getNodeType() != Node.ELEMENT_NODE) {
			return false;
		}
		((Element) node).setAttribute(attrName, attrValue);
		return true;
	}
}
