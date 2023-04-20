/**
 *  MIT License
 *  
 *  Copyright (c) 2016 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package org.vorpal.blade.services.proxy.registrar;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map.Entry;
import java.util.logging.Level;

import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;
import javax.servlet.sip.annotation.SipApplicationSessionScoped;

import org.vorpal.blade.framework.config.SettingsManager;

@SipApplicationSessionScoped
public class ProxyRegistrar implements Serializable {

	private class ContactInfo implements Serializable {
		private static final long serialVersionUID = 1L;
		public Address address;
		public Long timestamp;

		public ContactInfo(Address address, Long timestamp) {
			this.address = address;
			this.timestamp = timestamp;
		}
	}

	private static final long serialVersionUID = 1L;
	private HashMap<URI, ContactInfo> contactsMap = new HashMap<>();
	private int maxExpires;
	private List<String> allow;

	public List<String> getAllow() {
		return allow;
	}

	public void setAllow(List<String> allow) {
		this.allow = allow;
	}

	public SipServletMessage copyAllow(SipServletMessage msg) {
		List<String> allowHeader = msg.getHeaderList("Allow");
		if (allowHeader != null) {
			allow = new ArrayList<String>(allowHeader);
		} else {
			allow = ProxyRegistrarServlet.settingsManager.getCurrent().defaultAllow;
		}
		return msg;
	}

	public SipServletMessage pasteAllow(SipServletMessage msg) {
		for (String value : allow) {
			msg.addHeader("Allow", value);
		}
		return msg;
	}

	public HashMap<URI, ContactInfo> getContactsMap() {
		return contactsMap;
	}

	public void setContactsMap(HashMap<URI, ContactInfo> contactsMap) {
		this.contactsMap = contactsMap;
	}

//	public HashMap<URI, Long> getContactsMap() {
//		return contactsMap;
//	}
//
//	public void setContactsMap(HashMap<URI, Long> contactsMap) {
//		this.contactsMap = contactsMap;
//	}

	public int getMaxExpires() {
		return maxExpires;
	}

	public void setMaxExpires(int maxExpires) {
		this.maxExpires = maxExpires;
	}

	public void updateContacts(SipServletRequest register) throws ServletParseException {
		ProxyRegistrarServlet.sipLogger.warning(register, "updateContacts begin...");

		ListIterator<Address> contactsIterator = register.getAddressHeaders("Contact");

		while (contactsIterator.hasNext()) {
			Address contact = contactsIterator.next();

			int expires = (contact.getExpires() >= 0) ? contact.getExpires() : register.getExpires();
			ProxyRegistrarServlet.sipLogger.warning(register, "calculated expires: " + expires);

			if (expires > 0) { // add contact
				ProxyRegistrarServlet.sipLogger.warning(register, "adding contact: " + contact.toString());
				contactsMap.put(contact.getURI(),
						new ContactInfo(contact, System.currentTimeMillis() + (expires * 1000)));
			} else { // remove contact
				ProxyRegistrarServlet.sipLogger.severe(register, "removing contact: " + contact.getURI());
				contactsMap.remove(contact.getURI());
			}
		}
		ProxyRegistrarServlet.sipLogger.warning(register, "...updateContacts end.");

	}

	public int calculateExpires(Long timestamp) {
		return (int) Math.ceil((timestamp - System.currentTimeMillis()) / 1000.0);
	}

	public List<Address> getAddressContacts() throws ServletParseException {
		LinkedList<Address> contacts = new LinkedList<Address>();
		SipFactory sipFactory = SettingsManager.sipFactory;

		Iterator<Entry<URI, ContactInfo>> itr = contactsMap.entrySet().iterator();
		while (itr.hasNext()) {
			Entry<URI, ContactInfo> entry = itr.next();
			entry.getKey();
			ContactInfo contactInfo = entry.getValue();
			Long timestamp = entry.getValue().timestamp;
			int expires = calculateExpires(timestamp);

			if (expires <= 0) {
				// remove expired contact
				itr.remove();
			} else {
				// add expiration parameter
				Address contactWithExpiration = sipFactory.createAddress(contactInfo.address.toString());
				contactWithExpiration.setExpires(expires);
				contacts.add(contactWithExpiration);
				maxExpires = Math.max(expires, maxExpires);
			}
		}

		return contacts;
	}

	public List<URI> getURIContacts() throws ServletParseException {
		LinkedList<URI> contacts = new LinkedList<URI>();
		Iterator<Entry<URI, ContactInfo>> itr = contactsMap.entrySet().iterator();
		while (itr.hasNext()) {
			Entry<URI, ContactInfo> entry = itr.next();
			URI uri = entry.getKey();
			Long timestamp = entry.getValue().timestamp;
			int expires = calculateExpires(timestamp);

			if (expires <= 0) {
				// remove expired contact
				if (ProxyRegistrarServlet.sipLogger.isLoggable(Level.FINER)) {
					ProxyRegistrarServlet.sipLogger.finer("contact has expired: " + uri.toString());
				}
				itr.remove();
			} else {
				contacts.add(uri);
			}
		}

		if (ProxyRegistrarServlet.sipLogger.isLoggable(Level.FINER)) {
			ProxyRegistrarServlet.sipLogger.finer("Contacts found " + Arrays.toString(contacts.toArray()));
		}
		return contacts;
	}

}
