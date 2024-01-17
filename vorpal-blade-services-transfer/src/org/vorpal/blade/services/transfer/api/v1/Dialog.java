package org.vorpal.blade.services.transfer.api.v1;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpSession;
import javax.servlet.sip.SipSession;

public class Dialog {
	public String id;
	public String remote;
	public String state;
	public String subscriber;
	public String protocol;
	public Map<String, String> attributes;

	public Dialog(SipSession sipSession) {
		protocol = "SIP";
		id = sipSession.getId();
		remote = sipSession.getRemoteParty().toString();
		state = sipSession.getState().toString();
		subscriber = sipSession.getSubscriberURI().toString();

		attributes = new HashMap<>();
		for (String name : sipSession.getAttributeNameSet()) {
			attributes.put(name, sipSession.getAttribute(name).toString());
		}
	}

	public Dialog(HttpSession httpSession) {
		protocol = "HTTP";
		id = httpSession.getId();

		attributes = new HashMap<>();

		String name, value;
		Iterator<String> itr = httpSession.getAttributeNames().asIterator();
		while (itr.hasNext()) {
			name = itr.next();
			value = httpSession.getAttribute(name).toString();
			attributes.put(name, value);
		}
	}

}
