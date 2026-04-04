package org.vorpal.blade.framework.v2.analytics;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import javax.jms.JMSException;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributeSelector.DialogType;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.logging.Logger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

@JsonPropertyOrder({ "enabled", "events" })
public class Analytics implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty(defaultValue = "false")
    private Boolean enabled = false;

    private Map<String, EventSelector> events = new HashMap<>();

    // For associating SIP with HTTP
    @JsonIgnore
	public static final ThreadLocal<SipServletRequest> sipServletRequest = new ThreadLocal<>();

	@JsonIgnore
	private static Integer appInstanceId = null;

	@JsonIgnore
	public static JmsPublisher jmsPublisher;

	public Analytics() {
	}

	public EventSelector createEventSelector(String event) {
		EventSelector evsel = new EventSelector();
		events.put(event, evsel);
		return evsel;
	}

	/**
	 * Creates an Event and applies any 'origin' attribute selectors.
	 * 
	 * @param eventName
	 * @param message
	 * @return
	 */
	public Event createEvent(String eventName, SipServletMessage message) {
		Event event = null;

		Long sessionId = getSessionId(message.getApplicationSession());

		event = new Event();
		event.setName(eventName);
		event.setSessionId(sessionId);

		event.setApplicationId(getAppInstanceId());

		EventSelector evsel = events.get(eventName);

		if (evsel != null) {
			AttributesKey attrsKey;
			for (AttributeSelector attrSel : evsel.getAttributes()) {

				if (true == DialogType.origin.equals(attrSel.getDialog())) {
					attrsKey = attrSel.findKey(message);
					if (attrsKey != null) {
						event.addAttribute(new Attribute(attrSel.getId(), attrsKey.key));
					}
				}

			}
		}

		return event;
	}

	public Event addDestinationAttributes(Event event, SipServletMessage message) {
		EventSelector evsel = events.get(event.getName());

		if (evsel != null) {
			AttributesKey attrsKey;
			for (AttributeSelector attrSel : evsel.getAttributes()) {

				if (false == DialogType.origin.equals(attrSel.getDialog())) {
					attrsKey = attrSel.findKey(message);
					if (attrsKey != null) {
						event.addAttribute(new Attribute(attrSel.getId(), attrsKey.key));
					}
				}
			}
		}

		return event;
	}

	public Event addDestinationAttributes(Event event, SipServletContextEvent ssce) {
		EventSelector evsel = events.get(event.getName());

		if (evsel != null) {
			AttributesKey attrsKey;
			for (AttributeSelector attrSel : evsel.getAttributes()) {

				if (false == DialogType.origin.equals(attrSel.getDialog())) {
					attrsKey = attrSel.findKey(ssce);
					if (attrsKey != null) {
						event.addAttribute(new Attribute(attrSel.getId(), attrsKey.key));
					}
				}
			}
		}

		return event;
	}

	public Event createEvent(String eventName, SipServletContextEvent context) {
		Event event = null;

		event = new Event();
		event.setName(eventName);
		event.setApplicationId(getAppInstanceId());

		EventSelector evsel = events.get(eventName);

		if (evsel != null) {
			AttributesKey attrsKey;
			for (AttributeSelector attrSel : evsel.getAttributes()) {
				if (true == DialogType.origin.equals(attrSel.getDialog())) {
					attrsKey = attrSel.findKey(context);
					if (attrsKey != null) {
						event.addAttribute(new Attribute(attrSel.getId(), attrsKey.key));
					}
				}
			}
		}

		return event;
	}

	public Event createEvent(String eventName, JsonNode jsonNode) {
		Event event = new Event();
		event.setName(eventName);
//		event.setSessionId(sessionId);
		event.setApplicationId(getAppInstanceId());

		EventSelector evsel = events.get(eventName);

		if (evsel != null) {
			AttributesKey attrsKey;
			for (AttributeSelector attrSel : evsel.getAttributes()) {

				if (true == DialogType.origin.equals(attrSel.getDialog())) {
					attrsKey = attrSel.findKey(jsonNode);
					if (attrsKey != null) {
						event.addAttribute(new Attribute(attrSel.getId(), attrsKey.key));
					}
				}

			}
		}

		if (evsel != null) {
			List<String> keys = new ArrayList<>();
			Iterator<String> iterator = jsonNode.fieldNames();
			iterator.forEachRemaining(e -> {
				keys.add(e);
			});
		}

		return event;
	}

	public Event addDestinationAttributes(Event event, HttpServletResponse response) {
		return addDestinationAttributes(event, response, null);
	}

	public Event addDestinationAttributes(Event event, HttpServletResponse response, byte[] responseBody) {
		EventSelector evsel = events.get(event.getName());

		if (evsel != null) {
			AttributesKey attrsKey;
			for (AttributeSelector attrSel : evsel.getAttributes()) {

				if (false == DialogType.origin.equals(attrSel.getDialog())) {
					attrsKey = attrSel.findKey(response, responseBody);
					if (attrsKey != null) {
						event.addAttribute(new Attribute(attrSel.getId(), attrsKey.key));
					}
				}
			}
		}

		return event;
	}

	@JsonPropertyDescription("Map of analytics event definitions keyed by event name")
	public Map<String, EventSelector> getEvents() {
		return events;
	}

	public Analytics setEvents(Map<String, EventSelector> events) {
		this.events = events;
		return this;
	}

	public void sendEvent(Event event) {
		try {

			if (jmsPublisher != null) {
				jmsPublisher.send(event);
			}

		} catch (JMSException ex) {
			ex.printStackTrace();
		}
	}

	public static int getAppInstanceId() {
		if (appInstanceId == null) {
			appInstanceId = ThreadLocalRandom.current().nextInt();
		}
		return appInstanceId;
	}

	public static JmsPublisher getJmsPublisher() {
		return jmsPublisher;
	}

	public static void setJmsPublisher(JmsPublisher jmsPublisher) {
		Analytics.jmsPublisher = jmsPublisher;
	}

	@JsonPropertyDescription("Enable or disable analytics event collection")
	public Boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

	public static void setAppInstanceId(Integer appInstanceId) {
		Analytics.appInstanceId = appInstanceId;
	}

	private static long combineToLong(long timestamp, int sessionId) {
		// Convert timestamp to 32 bits (takes the lower 32 bits)
		int timestamp32 = (int) timestamp;

		// Combine: shift timestamp to upper 32 bits, OR with other value in lower 32
		// bits. Use 0xFFFFFFFFL mask to prevent sign extension of otherValue
		return ((long) timestamp32 << 32) | (sessionId & 0xFFFFFFFFL);
	}

// Helper method to extract the timestamp back
	private static int extractTimestamp(long combined) {
		return (int) (combined >>> 32);
	}

// Helper method to extract the other value back
	private static int extractSessionId(long combined) {
		return (int) combined;
	}

	public static Long getSessionId(SipApplicationSession appSession) {

		Long sessionId = (Long) appSession.getAttribute("ANALYTICS_SESSION");
		if (sessionId == null) {
			String vTimestamp = Callflow.getVorpalTimestamp(appSession);
			Long timestamp = Long.parseLong(vTimestamp, 16);
			Integer otherValue = Integer.parseUnsignedInt(Callflow.getVorpalSessionId(appSession), 16);
			sessionId = combineToLong(timestamp, otherValue);
			appSession.setAttribute("ANALYTICS_SESSION", sessionId);
		}
		return sessionId;
	}

	public static Session createSession(SipServletMessage msg) {
		Session session = new Session();
		long sessionId = getSessionId(msg.getApplicationSession());
		session.setId(sessionId);
		String strTimestamp = Callflow.getVorpalTimestamp(msg.getApplicationSession());
		long timestamp = Long.parseLong(strTimestamp, 16);
		Date date = new Date(timestamp);

		session.setApplicationId(getAppInstanceId());
		session.setCreated(null);
		session.setCreated(date);

		return session;

	}

	public static void applicationStart() {
		if (Analytics.jmsPublisher != null) {
			Analytics.jmsPublisher.applicationStart();
		}
	}

	public static void applicationStop() {
		if (Analytics.jmsPublisher != null) {
			Analytics.jmsPublisher.applicationStop();
		}
	}

	public static void sessionStart(SipServletMessage msg) {
		if (Analytics.jmsPublisher != null) {
			Analytics.jmsPublisher.sessionStart(msg);
		}
	}

	public static void sessionStop(SipServletMessage msg) {
		if (Analytics.jmsPublisher != null) {
			Analytics.jmsPublisher.sessionStop(msg);
		}
	}

}
