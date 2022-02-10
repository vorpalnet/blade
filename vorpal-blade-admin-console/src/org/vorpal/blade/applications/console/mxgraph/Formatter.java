package org.vorpal.blade.applications.console.mxgraph;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

public class Formatter {

	public static final Document xmlDocument(String xmlString) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setValidating(false);
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document xmlDoc = db.parse(new InputSource(new StringReader(xmlString)));
		return xmlDoc;
	}

	public static final String xmlPrettyPrint(Document xmlDoc) throws Exception {
		Transformer tf = TransformerFactory.newInstance().newTransformer();
		tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		tf.setOutputProperty(OutputKeys.INDENT, "yes");
		tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
		Writer out = new StringWriter();
		tf.transform(new DOMSource(xmlDoc), new StreamResult(out));
		return out.toString();
	}

	public static final String xmlPrettyPrint(String xmlString) throws Exception {
		return xmlPrettyPrint(xmlDocument(xmlString));
	}

	public static final void xmlSaveToFile(String xmlFilename, Document xmlDocument) throws Exception {
		try (PrintWriter out = new PrintWriter(xmlFilename)) {
			out.println(xmlPrettyPrint(xmlDocument));
		}
	}

	public static final void xmlSaveToFile(String xmlFilename, String xmlString) throws Exception {
		xmlSaveToFile(xmlFilename, xmlDocument(xmlString));
	}

}
