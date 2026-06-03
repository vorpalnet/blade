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
	private static Long appInstanceId = null;

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

		Long vorpalId = getVorpalId(message.getApplicationSession());

		event = new Event();
		event.setName(eventName);
		event.setVorpalId(vorpalId);

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

	public static long getAppInstanceId() {
		if (appInstanceId == null) {
			appInstanceId = ThreadLocalRandom.current().nextLong();
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

	public static void setAppInstanceId(Long appInstanceId) {
		Analytics.appInstanceId = appInstanceId;
	}

	/// The cluster-unique vorpal-id for the call (the X-Vorpal-ID Callflow mints
	/// at first-touch), as a long. This is the correlator the analytics consumer
	/// maps to the DB-assigned session primary key. Returns null if the
	/// application session carries no vorpal-id.
	public static Long getVorpalId(SipApplicationSession appSession) {
		String hex = Callflow.getVorpalSessionId(appSession);
		if (hex == null) {
			return null;
		}
		try {
			return Long.parseLong(hex, 16);
		} catch (NumberFormatException ex) {
			Callflow.getSipLogger().warning("Analytics.getVorpalId - unparseable vorpal-id '" + hex + "'");
			return null;
		}
	}

	public static Session createSession(SipServletMessage msg) {
		Session session = new Session();
		Long vorpalId = getVorpalId(msg.getApplicationSession());
		if (vorpalId != null) {
			session.setVorpalId(vorpalId);
		}
		session.setApplicationId(getAppInstanceId());
		session.setCreated(new Date());
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
