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
import org.vorpal.blade.framework.v2.logging.Logger;

public class Registrar implements Serializable {
	private static final long serialVersionUID = -9141916534493575461L;
	public Map<String, ContactInfo> contactsMap = new HashMap<>();
	private int maxExpires = 0;
	private static Logger sipLogger = Callflow.getSipLogger();
//	private List<String> allowHeaders = new LinkedList<>();

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
//			if (Callflow.getSipLogger().isLoggable(Level.FINER)) {
//				Callflow.getSipLogger().finer(request, "Contact: " + info.getAddress().getURI());
//			}
			list.add(info.getAddress().getURI());
		}
		return list;
	}

	public SipServletResponse updateContactz(SipServletRequest registerRequest) throws ServletParseException {
		SipServletResponse response;
		response = registerRequest.createResponse(200);
		return response;
	}

	public SipServletResponse updateContacts(SipServletRequest registerRequest) throws ServletParseException {
		List<String> allowHeaders = null;

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
//				if (sipLogger.isLoggable(Level.FINER)) {
//					sipLogger.finer(registerRequest, "Registrar.updateContacts - put strUri=" + strUri + ", contact="
//							+ copiedAddress + ", expires=" + expires);
//				}
				contactsMap.put(strUri, new ContactInfo(copiedAddress, expiration(expires)));
			} else {
//				if (sipLogger.isLoggable(Level.FINER)) {
//					sipLogger.finer(registerRequest, "Registrar.updateContacts - remove strUri=" + strUri);
//				}
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

//				if (sipLogger.isLoggable(Level.FINER)) {
//					sipLogger.finer(registerRequest,
//							"Registrar.updateContacts - removing expired contact: " + entry.getKey());
//				}

				itr.remove();
			}
		}

//		if (sipLogger.isLoggable(Level.FINER)) {
//			sipLogger.finer(registerRequest, "Registrar.updateContacts - contactsMap.size=" + contactsMap.size());
//		}

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(registerRequest, "Registrar.updateContacts - contactsMap.size=" + contactsMap.size());
		}

		if (contactsMap.size() == 0) {
			// invalidate in one minute

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(registerRequest,
						"Registrar.updateContacts - appSession.setInvalidateWhenReady(true), appSession.setExpires(1)");
			}

			appSession.setInvalidateWhenReady(true);
			appSession.setExpires(1);
		} else {

			int exp = (int) Math.ceil(maxExpires / 60.0);
			allowHeaders = registerRequest.getHeaderList("Allow");

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(registerRequest,
						"Registrar.updateContacts - appSession.setInvalidateWhenReady(false), appSession.setExpires("
								+ exp + ")");
			}

			appSession.setInvalidateWhenReady(false);
			appSession.setExpires(exp);

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

//				if (sipLogger.isLoggable(Level.FINER)) {
//					sipLogger.finer(registerRequest,
//							"Registrar.updateContacts - addAddressHeader contact=" + updatedContact);
//				}

				response.addAddressHeader("Contact", updatedContact, false);

			} else {
				// sloppy, fix later
				response.setExpires(Integer.parseInt(this.calculateExpires(contactInfo.getExpiration())));

				Address updatedContact = PRServlet.getSipFactory().createAddress(contact.toString());

//				if (sipLogger.isLoggable(Level.FINER)) {
//					sipLogger.finer(registerRequest, "Registrar.updateContacts - setExpires expires="
//							+ Integer.parseInt(this.calculateExpires(contactInfo.getExpiration())));
//					sipLogger.finer(registerRequest,
//							"Registrar.updateContacts - addAddressHeader contact=" + updatedContact);
//				}

				response.addAddressHeader("Contact", updatedContact, false);
			}

		}

		String allowHeader = PRServlet.settingsManager.getCurrent().allowHeader;
		if (allowHeader != null) {
			response.setHeader("Allow", PRServlet.settingsManager.getCurrent().allowHeader);
		}

		return response;
	}

}
