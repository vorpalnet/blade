package org.vorpal.blade.framework.v2.analytics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import javax.jms.JMSException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletMessage;

import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.AttributesKey;
import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Analytics {

	public enum Level {
		disabled, modest, verbose, complete
	};

	public Level level;

	public String jmsFactory;
	public String jmsQueue;
	public Map<String, EventSelector> events = new HashMap<>();

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

	public Event createEvent(String eventName, SipServletMessage message) {

		Event event = new Event();
		event.setName(eventName);

		event.setApplicationId(getAppInstanceId());

		EventSelector evsel = events.get(eventName);

		AttributesKey attrsKey;
		for (AttributeSelector attrSel : evsel.getAttributes()) {
			attrsKey = attrSel.findKey(message);
			if (attrsKey != null) {
				event.addAttribute(new Attribute(attrSel.getId(), attrsKey.key));
			}
		}

		return event;
	}

	public Event createEvent(String eventName, SipServletContextEvent context) {

		Event event = new Event();
		event.setName(eventName);

		event.setApplicationId(getAppInstanceId());

		for (String key : events.keySet()) {
			SettingsManager.sipLogger.warning("Analytics.createEvent - events key=" + key);
		}

		EventSelector evsel = events.get(eventName);

		if (evsel != null) {
			AttributesKey attrsKey;
			for (AttributeSelector attrSel : evsel.getAttributes()) {
				attrsKey = attrSel.findKey(context);
				if (attrsKey != null) {
					event.addAttribute(new Attribute(attrSel.getId(), attrsKey.key));
				}
			}
		}

		return event;
	}

	public String getJmsFactory() {
		return jmsFactory;
	}

	public Analytics setJmsFactory(String jmsFactory) {
		this.jmsFactory = jmsFactory;
		return this;
	}

	public String getJmsQueue() {
		return jmsQueue;
	}

	public Analytics setJmsQueue(String jmsQueue) {
		this.jmsQueue = jmsQueue;
		return this;
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
			jmsPublisher.send(event);

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

}
