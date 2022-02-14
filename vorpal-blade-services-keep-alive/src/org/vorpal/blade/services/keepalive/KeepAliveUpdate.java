package org.vorpal.blade.services.keepalive;

import java.io.IOException;
import java.util.Iterator;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

/**
 * This class manages keep-alive requests by using UPDATEs.
 * 
 * @author Jeff McDonald
 */
public class KeepAliveUpdate extends KeepAliveCallflow {

	/**
	 * This method likes to keep the tea party going by going around the table and
	 * giving everyone an UPDATE. If for some reason the UPDATE fails (which it
	 * shouldn't), the refresher algorithm is switched to INVITE.
	 *
	 */

	@Override
	public void handle(SipSession sipSession) {

		try {
			Iterator<SipSession> itr = (Iterator<SipSession>) sipSession.getApplicationSession().getSessions();
			SipSession ss;
			SipServletRequest update;
			while (itr.hasNext()) {
				ss = itr.next();
				update = ss.createRequest(UPDATE);
				sendRequest(update, (response) -> {
					if (failure(response)) {
						// Not good! Let's change the keep-alive to re-invites.
						sipLogger.warning(response, "This endpoint claims to allow UPDATE, but replied back with "
								+ response.getStatus() + " " + response.getReasonPhrase());
						sipLogger.warning(response, "Changing keep-alive refresher from UPDATE to INVITE for to: "
								+ response.getTo() + ", addr: " + response.getRemoteAddr());
						sipSession.getKeepAlive().setRefreshCallback(new KeepAliveReinvite());
					}
				});
			}
		} catch (ServletException | IOException e) {
			sipLogger.logStackTrace(e);
		}

	}
}
