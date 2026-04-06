package org.vorpal.blade.applications.console.tuning;

import java.io.File;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for SIP protocol timer settings.
 *
 * These settings are stored in sipserver.xml and control SIP transaction
 * timeouts per RFC 3261. They directly affect call setup times and
 * retransmission behavior.
 */
@Path("/api/v1/sip-timers")
@Tag(name = "SIP Timers", description = "SIP protocol timeout and behavior settings")
public class SipTimerSettings {

	private static final ObjectMapper mapper = new ObjectMapper();
	private static final String SIPSERVER_PATH = "./config/custom/sipserver.xml";

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get SIP timer and protocol settings")
	public Response get() {
		try {
			File file = new File(SIPSERVER_PATH);
			if (!file.exists()) {
				return Response.ok("{\"error\":\"sipserver.xml not found\"}").build();
			}

			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
			ObjectNode result = mapper.createObjectNode();

			// Timers
			result.put("t1", getElementInt(doc, "t1-timeout-interval", 500));
			result.put("t2", getElementInt(doc, "t2-timeout-interval", 4000));
			result.put("t4", getElementInt(doc, "t4-timeout-interval", 5000));
			result.put("timerB", getElementInt(doc, "timer-b-timeout", 32000));
			result.put("timerF", getElementInt(doc, "timer-f-timeout", 32000));
			result.put("timerL", getElementInt(doc, "timer-l-timeout", 32000));
			result.put("timerM", getElementInt(doc, "timer-m-timeout", 32000));
			result.put("timerN", getElementInt(doc, "timer-n-timeout", 32000));

			// Behavior
			result.put("defaultBehavior", getElementText(doc, "default-behavior", "proxy"));
			result.put("staleSessionHandling", getElementText(doc, "stale-session-handling", "error"));
			result.put("retryAfterValue", getElementInt(doc, "retry-after-value", 180));
			result.put("maxAppSessionLifetime", getElementInt(doc, "max-application-session-lifetime", -1));
			result.put("enableLocalDispatch", getElementBool(doc, "enable-local-dispatch", false));
			result.put("enableSipOutbound", getElementBool(doc, "enable-sip-outbound", true));
			result.put("enableDnsSrvLookup", getElementBool(doc, "enable-dns-srv-lookup", false));
			result.put("enableTimerAffinity", getElementBool(doc, "enable-timer-affinity", false));
			result.put("enableRPort", getElementBool(doc, "enable-rport", false));
			result.put("engineCallStateCache", getElementBool(doc, "engine-call-state-cache-enabled", true));
			result.put("useHeaderForm", getElementText(doc, "use-header-form", "long"));
			result.put("serverHeader", getElementText(doc, "server-header", "none"));
			result.put("enableSend100ForNonInvite", getElementBool(doc, "enable-send-100-for-non-invite", true));

			return Response.ok(mapper.writeValueAsString(result)).build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Update SIP timer and protocol settings")
	public Response update(String body) {
		try {
			ObjectNode input = (ObjectNode) mapper.readTree(body);
			File file = new File(SIPSERVER_PATH);
			if (!file.exists()) {
				return Response.status(404).entity("{\"error\":\"sipserver.xml not found\"}").build();
			}

			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);

			// Timers
			setElement(doc, "t1-timeout-interval", input, "t1");
			setElement(doc, "t2-timeout-interval", input, "t2");
			setElement(doc, "t4-timeout-interval", input, "t4");
			setElement(doc, "timer-b-timeout", input, "timerB");
			setElement(doc, "timer-f-timeout", input, "timerF");
			setElement(doc, "timer-l-timeout", input, "timerL");
			setElement(doc, "timer-m-timeout", input, "timerM");
			setElement(doc, "timer-n-timeout", input, "timerN");

			// Behavior
			setElement(doc, "default-behavior", input, "defaultBehavior");
			setElement(doc, "stale-session-handling", input, "staleSessionHandling");
			setElement(doc, "retry-after-value", input, "retryAfterValue");
			setElement(doc, "max-application-session-lifetime", input, "maxAppSessionLifetime");
			setElement(doc, "enable-local-dispatch", input, "enableLocalDispatch");
			setElement(doc, "enable-sip-outbound", input, "enableSipOutbound");
			setElement(doc, "enable-dns-srv-lookup", input, "enableDnsSrvLookup");
			setElement(doc, "enable-timer-affinity", input, "enableTimerAffinity");
			setElement(doc, "enable-rport", input, "enableRPort");
			setElement(doc, "engine-call-state-cache-enabled", input, "engineCallStateCache");
			setElement(doc, "use-header-form", input, "useHeaderForm");
			setElement(doc, "server-header", input, "serverHeader");
			setElement(doc, "enable-send-100-for-non-invite", input, "enableSend100ForNonInvite");

			// Write back
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
			transformer.transform(new DOMSource(doc), new StreamResult(file));

			return Response.ok("{\"success\":true}").build();

		} catch (Exception e) {
			return errorResponse(e);
		}
	}

	private String getElementText(Document doc, String tagName, String dflt) {
		NodeList nodes = doc.getElementsByTagName(tagName);
		if (nodes.getLength() > 0 && nodes.item(0).getTextContent() != null) {
			return nodes.item(0).getTextContent().trim();
		}
		return dflt;
	}

	private int getElementInt(Document doc, String tagName, int dflt) {
		try {
			return Integer.parseInt(getElementText(doc, tagName, String.valueOf(dflt)));
		} catch (NumberFormatException e) {
			return dflt;
		}
	}

	private boolean getElementBool(Document doc, String tagName, boolean dflt) {
		String text = getElementText(doc, tagName, String.valueOf(dflt));
		return "true".equalsIgnoreCase(text);
	}

	private void setElement(Document doc, String tagName, ObjectNode input, String field) {
		if (!input.has(field) || input.get(field).isNull()) return;
		NodeList nodes = doc.getElementsByTagName(tagName);
		if (nodes.getLength() > 0) {
			nodes.item(0).setTextContent(input.get(field).asText());
		}
	}

	private Response errorResponse(Exception e) {
		String msg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Unknown error";
		return Response.serverError().entity("{\"error\":\"" + msg + "\"}").build();
	}
}
