// Copyright (c) 2016 VORPAL.ORG
package org.vorpal.blade.services.proxyregistrar;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map.Entry;

import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipApplicationSessionScoped;

@SipApplicationSessionScoped
public class ProxyRegistrar implements Serializable {
	private static final long serialVersionUID = 1L;
	private HashMap<Address, Long> contacts_map = new HashMap<Address, Long>();
	private int max_expires;

	public HashMap<Address, Long> getContacts_map() {
		return contacts_map;
	}

	public void setContacts_map(HashMap<Address, Long> contacts_map) {
		this.contacts_map = contacts_map;
	}

	public int getMax_expires() {
		return max_expires;
	}

	public void setMax_expires(int max_expires) {
		this.max_expires = max_expires;
	}

	public int getMaxExpires() {
		return max_expires;
	}

	public void updateContacts(SipServletRequest register) throws ServletParseException {
		ListIterator<Address> contactsIterator = register.getAddressHeaders("Contact");

		while (contactsIterator.hasNext()) {
			Address contact = contactsIterator.next();
			int expires = (contact.getExpires() >= 0) ? contact.getExpires() : register.getExpires();
			if (expires > 0) { // add contact
				contacts_map.put(contact, System.currentTimeMillis() + (expires * 1000));
			} else { // remove contact
				contacts_map.remove(contact);
			}
		}

	}

	public int calculateExpires(Long timestamp) {
		return (int) Math.ceil((timestamp - System.currentTimeMillis()) / 1000.0);
	}

	public List<Address> getContacts() throws ServletParseException {
		LinkedList<Address> contacts = new LinkedList<Address>();
		SipFactory sipFactory = ProxyRegistrarServlet.sipFactory;

		Iterator<Entry<Address, Long>> itr = contacts_map.entrySet().iterator();
		while (itr.hasNext()) {
			Entry<Address, Long> entry = itr.next();
			Address address = entry.getKey();
			Long timestamp = entry.getValue();
			int expires = calculateExpires(timestamp);

			if (expires <= 0) {
				// remove expired contact
				itr.remove();
			} else {
				// add expiration parameter
				Address contactWithExpiration = sipFactory.createAddress(address.toString());
				contactWithExpiration.setExpires(expires);
				contacts.add(contactWithExpiration);

				max_expires = Math.max(expires, max_expires);
			}
		}

		return contacts;
	}

}
