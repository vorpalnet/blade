package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.Serializable;
import java.io.StringReader;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;

import javax.servlet.sip.SipServletRequest;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

import org.vorpal.blade.framework.v2.config.FormLayoutGroup;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.configuration.Context;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Extracts a value from an XML payload via XPath. The `attribute`
/// field is the XPath expression. Optional `namespaces` map binds
/// prefixes to URIs. Stores the raw extracted text under this
/// selector's `id`. Chain a [RegexSelector] after if you need to
/// further parse the result.
///
/// Reads from String payload (e.g. SOAP response body) or the SIP
/// request body when the payload is a [SipServletRequest].
@JsonPropertyOrder({ "type", "id", "description", "attribute", "namespaces",
		"index", "applicationSession" })
@FormLayoutGroup({ "id", "attribute" })
public class XmlSelector extends Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	protected Map<String, String> namespaces;

	public XmlSelector() {
	}

	@JsonPropertyDescription("Optional namespace prefix → URI map for XPath resolution")
	public Map<String, String> getNamespaces() { return namespaces; }
	public void setNamespaces(Map<String, String> namespaces) { this.namespaces = namespaces; }

	@Override
	public void extract(Context ctx, Object payload) {
		if (attribute == null) return;

		String text = textPayload(payload);
		if (text == null || text.isEmpty()) return;

		Logger sipLogger = SettingsManager.getSipLogger();

		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			if (namespaces != null && !namespaces.isEmpty()) {
				dbf.setNamespaceAware(true);
			}
			Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(text)));

			XPath xp = XPathFactory.newInstance().newXPath();
			if (namespaces != null && !namespaces.isEmpty()) {
				xp.setNamespaceContext(new SimpleNamespaceContext(namespaces));
			}

			String raw = xp.evaluate(attribute, doc);
			if (raw == null || raw.isEmpty()) return;

			store(ctx, id, raw);

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer("XmlSelector[" + id + "] xpath=" + attribute + " value=" + raw);
			}
		} catch (Exception e) {
			sipLogger.warning("XmlSelector[" + id + "] failed: " + e.getMessage());
		}
	}

	private static String textPayload(Object payload) {
		if (payload instanceof String) return (String) payload;
		if (payload instanceof byte[]) return new String((byte[]) payload);
		if (payload instanceof SipServletRequest) {
			try {
				Object content = ((SipServletRequest) payload).getContent();
				if (content instanceof String) return (String) content;
				if (content instanceof byte[]) return new String((byte[]) content);
			} catch (Exception ignore) {
			}
		}
		return null;
	}

	private static class SimpleNamespaceContext implements NamespaceContext {
		private final Map<String, String> prefixToUri;

		SimpleNamespaceContext(Map<String, String> prefixToUri) {
			this.prefixToUri = prefixToUri;
		}

		@Override
		public String getNamespaceURI(String prefix) {
			return prefixToUri.getOrDefault(prefix, javax.xml.XMLConstants.NULL_NS_URI);
		}

		@Override
		public String getPrefix(String namespaceURI) {
			for (Map.Entry<String, String> e : prefixToUri.entrySet()) {
				if (e.getValue().equals(namespaceURI)) return e.getKey();
			}
			return null;
		}

		@Override
		public Iterator<String> getPrefixes(String namespaceURI) {
			return prefixToUri.keySet().iterator();
		}
	}
}
