package org.vorpal.blade.services.analytics.sip;

import java.util.HashMap;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.AttributeSelector;

public class Analytics {
	public enum Events {
		exception, servletCreated, servletDestroyed
	};

	public Boolean enabled;
	public String jmsFactory;
	public String jmsQueue;

	private Map<Events, EventSelector> events; 

	public Analytics() {

		events = new HashMap<>();
		this.enabled = Boolean.TRUE;
		this.jmsFactory = "jms/TestConnectionFactory";
		this.jmsQueue = "jms/TestJMSQueue";

		// add support to AttributeSelector for Exception & SipServletEvent
		
	}

	public Boolean getEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

	public String getJmsFactory() {
		return jmsFactory;
	}

	public void setJmsFactory(String jmsFactory) {
		this.jmsFactory = jmsFactory;
	}

	public String getJmsQueue() {
		return jmsQueue;
	}

	public void setJmsQueue(String jmsQueue) {
		this.jmsQueue = jmsQueue;
	}

	public Map<Events, EventSelector> getEvents() {
		return events;
	}

	public void setEvents(Map<Events, EventSelector> events) {
		this.events = events;
	}
	
	
	
	

}
