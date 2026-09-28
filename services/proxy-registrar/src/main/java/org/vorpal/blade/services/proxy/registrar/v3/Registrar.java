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
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Logger;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

public class Registrar implements Serializable {
	private static final long serialVersionUID = -9141916534493575461L;
	public Map<String, ContactInfo> contactsMap = new HashMap<>();

	/// The address of record these contacts belong to, for the events.
	private String aor;
	private static Logger sipLogger = Callflow.getSipLogger();

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

	/// The account's currently registered contacts.
	///
	/// Expired entries are skipped. `updateContacts` prunes the map, but it only
	/// runs on a REGISTER — and a phone that dies without deregistering never
	/// sends another one. Without this check an INVITE would keep forking to a
	/// contact whose registration lapsed hours ago.
	public List<URI> getContacts(SipServletRequest request) {
		List<URI> list = new LinkedList<>();
		long currentTime = System.currentTimeMillis();
		for (ContactInfo info : contactsMap.values()) {
			if (info.getExpiration() > currentTime) {
				list.add(info.getAddress().getURI());
			}
		}
		return list;
	}

//	public SipServletResponse updateContacts(SipServletRequest registerRequest) throws ServletParseException {
//		SipServletResponse response;
//		response = registerRequest.createResponse(200);
//		return response;
//	}

	public SipServletResponse updateContacts(SipServletRequest registerRequest) throws ServletParseException {

		SipApplicationSession appSession = registerRequest.getApplicationSession();
		List<Address> contacts = registerRequest.getAddressHeaderList("Contact");
		aor = String.valueOf(registerRequest.getTo().getURI());

		// add or remove contacts
		int expires;
		String strUri;
		Address copiedAddress;
		for (Address address : contacts) {
			strUri = address.getURI().toString();
			expires = expires(registerRequest, address);
			if (expires > 0) {
				copiedAddress = Callflow.getSipFactory().createAddress(address.toString()); // can only edit copies
//				if (sipLogger.isLoggable(Level.FINER)) {
//					sipLogger.finer(registerRequest, "Registrar.updateContacts - put strUri=" + strUri + ", contact="
//							+ copiedAddress + ", expires=" + expires);
//				}
				if (contactsMap.put(strUri, new ContactInfo(copiedAddress, expiration(expires))) == null) {
					added(strUri, expires);
				}
			} else {
//				if (sipLogger.isLoggable(Level.FINER)) {
//					sipLogger.finer(registerRequest, "Registrar.updateContacts - remove strUri=" + strUri);
//				}
				if (contactsMap.remove(strUri) != null) {
					removed(strUri, "unregistered");
				}
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
				removed(entry.getKey(), "expired");
			}
		}

//		if (sipLogger.isLoggable(Level.FINER)) {
//			sipLogger.finer(registerRequest, "Registrar.updateContacts - contactsMap.size=" + contactsMap.size());
//		}

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(registerRequest, "Registrar.updateContacts - contactsMap.size=" + contactsMap.size());
		}

		if (contactsMap.size() == 0) {
			// The account has deregistered. Let the session expire in a minute
			// rather than marking it invalidate-when-ready: every REGISTER for
			// this account shares this one SipApplicationSession (keyed by
			// PRServlet.sessionKey), so tearing it down the instant the
			// deregister's own SipSession goes ready also destroys the call
			// state of any sibling REGISTER still waiting to be answered.
			//
			// A residual collision survives this fix and is NOT ours to close
			// here: a phone that re-registers on the same cadence as this
			// expiry (Bria cycles ~60s) can arrive while the old appSession is
			// mid-invalidation. That dispatch stalls past T1, the phone
			// retransmits, and the retransmission is processed as a fresh
			// request (new transaction, App Router re-run) and gets the 200.
			// The first packet's transaction is left unanswered, and OCCAS's
			// default-on RFC 4320 guard — a T2 (4s) timer per non-INVITE
			// server transaction that auto-sends 100 Trying
			// (enable-send100-for-non-invite-transaction, compiled-in true) —
			// fires on it, finds the request answered elsewhere, and logs
			// BEA-331601 "Client timer task failed ... This transaction has
			// been completed already". Harmless but ERROR-severity; the fix is
			// disabling that guard (Tuning app, SIP panel), not app code.

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(registerRequest,
						"Registrar.updateContacts - appSession.setInvalidateWhenReady(false), appSession.setExpires(1)");
			}

			appSession.setInvalidateWhenReady(false);
			appSession.setExpires(1);
		} else {

			// Outlive the longest-lived contact in the map, not merely the
			// longest interval THIS request asked for. One account can hold
			// contacts from several devices, so a second phone re-registering
			// for 60 seconds must not expire the whole record while the first
			// phone's hour-long registration still has time left. Floor of one
			// minute: setExpires(0) means never expire.
			long latest = 0;
			for (ContactInfo contactInfo : contactsMap.values()) {
				latest = Math.max(latest, contactInfo.getExpiration());
			}
			int exp = Math.max(1, (int) Math.ceil((latest - System.currentTimeMillis()) / 60000.0));

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

				// The container refuses setParameter on a Contact address it handed us
				// (AssertionError), so set "expires" on a copy built from its string form.
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

		String allowHeader = PRServlet.settingsManager.getCurrent().getAllowHeader();
		if (allowHeader != null) {
			response.setHeader("Allow", allowHeader);
		}

		return response;
	}

	/// The application session is expiring: it was set to outlive the longest
	/// contact, so whatever is still here lapsed without a REGISTER. Without
	/// this, an address that simply went quiet never produced a removal.
	public void lapsed() {
		long now = System.currentTimeMillis();
		Iterator<Entry<String, ContactInfo>> itr = contactsMap.entrySet().iterator();
		while (itr.hasNext()) {
			Entry<String, ContactInfo> entry = itr.next();
			if (entry.getValue().getExpiration() <= now) {
				itr.remove();
				removed(entry.getKey(), "expired");
			}
		}
	}

	/// A contact this address did not have. A refresh of one it has is not
	/// news, so it is not published.
	private void added(String contact, int expires) {
		String address = aor;
		Events.publish(BladeEventTypes.REGISTRATION_ADDED, address, data -> data
				.put("aor", address).put("contact", contact).put("expires", expires)
				.put("node", SettingsManager.getServerName()));
	}

	/// A contact gone: `unregistered` by an Expires 0, or `expired`, noticed
	/// on the address's next REGISTER or when its session expires ([#lapsed]).
	private void removed(String contact, String reason) {
		String address = aor;
		Events.publish(BladeEventTypes.REGISTRATION_REMOVED, address, data -> data
				.put("aor", address).put("contact", contact).put("reason", reason)
				.put("node", SettingsManager.getServerName()));
	}
}
