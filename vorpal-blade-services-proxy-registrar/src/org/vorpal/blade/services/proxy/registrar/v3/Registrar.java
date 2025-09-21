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
	private static final long serialVersionUID = -9141916534493575461L;
	public Map<String, ContactInfo> contactsMap = new HashMap<>();
	private int maxExpires = 0;
	private List<String> allowHeaders = null;

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
			allowHeaders = registerRequest.getHeaderList("Allow");
			appSession.setExpires((int) Math.ceil(maxExpires / 60.0));
			appSession.setInvalidateWhenReady(false);
		}

		// return an updated list of contacts
		SipServletResponse response = registerRequest.createResponse(200);

		Address contact;

		for (ContactInfo contactInfo : contactsMap.values()) {
			contact = contactInfo.getAddress();

			if (contactsMap.size() > 1) {

				// jwm - Failed to dispatch Sip message to servlet PRServlet
				// java.lang.AssertionError at
				// com.bea.wcp.sip.engine.ParameterableAdapter.setParameter(ParameterableAdapter.java:154)
				// exception when modifying expires, try a deep copy
				// contact.setParameter("expires",
				// this.calculateExpires(contactInfo.getExpiration()));
				// response.addAddressHeader("Contact", contact, false);

				// jwm - does this work?
				Address updatedContact = PRServlet.getSipFactory().createAddress(contact.toString());
				updatedContact.setParameter("expires", this.calculateExpires(contactInfo.getExpiration()));
				response.addAddressHeader("Contact", updatedContact, false);

			} else {
				// sloppy, fix later
				response.setExpires(Integer.parseInt(this.calculateExpires(contactInfo.getExpiration())));
			}

		}

		if (allowHeaders != null) {
			for (String allow : allowHeaders) {
				response.setHeader("Allow", allow);
			}
		}

		return response;
	}

}
