package org.vorpal.blade.services.analytics.jms;

import java.io.Serializable;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

public class EventDetailRecord implements Serializable {
	public String vorpalID;
	public String vorpalTimestamp;
	public ServletContext servletContext;
	public Map<String, String> events;

	public EventDetailRecord() {
	}

	public EventDetailRecord(SipServletMessage msg) {
	}

	public EventDetailRecord(SipServletRequest request) {
		this.vorpalID = request.getHeader("X-Vorpal-ID");
	}

	public EventDetailRecord(SipServletResponse response) {
	}

}
