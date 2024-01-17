/**
 * 
 */
package org.vorpal.blade.services.transfer.api.v1;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.http.HttpSession;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipSession;

/**
 * 
 */
public class Session {
	public String sessionId;
	public List<Dialog> dialogs;

	public Session(SipApplicationSession appSession) {
		Dialog dialog;

		sessionId = appSession.getId();
		dialogs = new LinkedList<>();

		Iterator<SipSession> sitr = (Iterator<SipSession>) appSession.getSessions("SIP");

		while (sitr.hasNext()) {
			dialog = new Dialog(sitr.next());
		}

		Iterator<HttpSession> hitr = (Iterator<HttpSession>) appSession.getSessions("HTTP");
		while (hitr.hasNext()) {
			dialog = new Dialog(hitr.next());
		}
	}
}
