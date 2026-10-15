package org.vorpal.blade.applications.dashboard;

import java.io.StringReader;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// A parked event as [BusServlet] lists it, read from the broker's XML
/// rendering of a message on the error queue.
final class ParkedEvents {

	private static final Logger logger = Logger.getLogger(ParkedEvents.class.getName());
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ParkedEvents() {
	}

	/// One parked message's headers, from the broker's XML rendering of it:
	/// `{id, at, type, subject, eventId, reason}`.
	static ObjectNode message(String xml) {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
			ObjectNode row = MAPPER.createObjectNode();
			row.put("id", text(doc, "JMSMessageID"));
			String at = text(doc, "JMSTimestamp");
			if (at != null && at.matches("\\d+")) {
				row.put("at", Long.parseLong(at));
			}
			NodeList props = doc.getElementsByTagNameNS("*", "property");
			for (int i = 0; i < props.getLength(); i++) {
				Element p = (Element) props.item(i);
				String value = p.getTextContent() == null ? null : p.getTextContent().trim();
				switch (p.getAttribute("name")) {
				case "eventType":
					row.put("type", value);
					break;
				case "eventSubject":
					row.put("subject", value);
					break;
				case "eventId":
					row.put("eventId", value);
					break;
				case "JMS_BEA_DeliveryFailureReason":
					row.put("reason", reason(value));
					break;
				default:
					break;
				}
			}
			return row;
		} catch (Exception e) {
			logger.log(Level.FINE, "unreadable parked message", e);
			return null;
		}
	}

	/// The broker's reason for parking a message, in words. 2 is the
	/// redelivery limit reached; any other code is shown as its number.
	static String reason(String code) {
		return "2".equals(code) ? "a subscriber failed it until the redelivery limit"
				: (code == null ? null : "broker reason " + code);
	}

	private static String text(Document doc, String local) {
		NodeList n = doc.getElementsByTagNameNS("*", local);
		return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
	}
}
