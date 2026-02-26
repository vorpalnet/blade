package org.vorpal.blade.framework.v2.analytics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import javax.jms.JMSException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributeSelector.DialogType;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({ "enabled", "loggingLevel", "events" })
public class Analytics {

	private Boolean enabled = false;

	private Map<String, EventSelector> events = new HashMap<>();

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

		if (Callflow.getSipLogger().isLoggable(Level.FINER)) {
			if (message instanceof SipServletRequest) {
				Callflow.getSipLogger().finer(message,
						"Analytics.createEvent #1 - eventName=" + eventName + ", message=" + message.getMethod());
			} else {
				Callflow.getSipLogger().finer(message, "Analytics.createEvent #1 - eventName=" + eventName //
						+ ", message=" + message.getMethod() + " " //
						+ ((SipServletResponse) message).getStatus() + " " //
						+ ((SipServletResponse) message).getReasonPhrase());
			}
		}

		event = new Event();
		event.setName(eventName);

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

	public Boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

	public static void setAppInstanceId(Integer appInstanceId) {
		Analytics.appInstanceId = appInstanceId;
	}

}
