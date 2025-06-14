package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;

import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.services.proxy.registrar.v3.junk.ContactInfo;

public class Registrar implements Serializable {
	private static final long serialVersionUID = 1L;
	public Map<String, ContactInfo> contactsMap = new HashMap<>();
	public int maxExpires = 0;

	// this is the bad one? yes, why? it's null?
	// public List<String> allowHeaders = null;

	private int expires(SipServletRequest registerRequest, Address contact) {
		String strExpires = null;
		strExpires = contact.getParameter("expires");
		strExpires = (null != strExpires) ? strExpires : registerRequest.getHeader("Expires");
		strExpires = (null != strExpires) ? strExpires : "0";
		return Integer.parseInt(strExpires);
	}

	private long expiration(int expires) {
		long currentTime = System.currentTimeMillis();
		return currentTime + (1000 * expires);
	}

	private String calculateExpires(Long timestamp) {
		int expires = (int) Math.ceil((timestamp - System.currentTimeMillis()) / 1000.0);
		return Integer.toString(expires);
	}

	public List<URI> getContacts(SipServletRequest request) {
		List<URI> list = new LinkedList<>();
		for (ContactInfo info : contactsMap.values()) {
			if (Callflow.getSipLogger().isLoggable(Level.FINER)) {
				Callflow.getSipLogger().finer(request, "Contact: " + info.getAddress().getURI());
			}
			list.add(info.getAddress().getURI());
		}
		return list;
	}

	public SipServletResponse updateContacts(SipServletRequest registerRequest) throws ServletParseException {
		SipApplicationSession appSession = registerRequest.getApplicationSession();
		List<Address> contacts = registerRequest.getAddressHeaderList("Contact");

		// add or remove contacts
		int expires;
		String strUri;
		Address copiedAddress;
		for (Address address : contacts) {
			strUri = address.getURI().toString();
			expires = expires(registerRequest, address);
			maxExpires = Math.max(maxExpires, expires);
			if (expires > 0) {
				copiedAddress = Callflow.getSipFactory().createAddress(address.toString()); // can only edit copies
				contactsMap.put(strUri, new ContactInfo(copiedAddress, expiration(expires)));
			} else {
				contactsMap.remove(strUri);
			}
		}

		// remove an expired contacts
		Long currentTime = System.currentTimeMillis();
		Iterator<Entry<String, ContactInfo>> itr = contactsMap.entrySet().iterator();
		Entry<String, ContactInfo> entry;
		while (itr.hasNext()) {
			entry = itr.next();
			if (entry.getValue().getExpiration() <= currentTime) {
				itr.remove();
			}
		}

		if (contactsMap.size() == 0) {
			// invalidate in one minute
			appSession.setInvalidateWhenReady(true);
			appSession.setExpires(1);
		} else {
			appSession.setExpires((int) Math.ceil(maxExpires / 60.0));
			appSession.setInvalidateWhenReady(false);
		}

		// return an updated list of contacts
		SipServletResponse response = registerRequest.createResponse(200);
		Address contact;
		int numOfContacts = contactsMap.size();
		for (ContactInfo contactInfo : contactsMap.values()) {
			contact = contactInfo.getAddress();
			if (numOfContacts > 1) {
				contact.setParameter("expires", this.calculateExpires(contactInfo.getExpiration()));
			}
			response.addAddressHeader("Contact", contact, false);
		}

		if (numOfContacts <= 1) {
			response.setExpires(registerRequest.getExpires());
		}

		List<String> allowHeaders = registerRequest.getHeaderList("Allow");
		if (allowHeaders != null) {
			for (String allow : allowHeaders) {
				response.addHeader("Allow", allow);
			}
		}

		return response;
	}

}
