package org.vorpal.blade.framework.v2.keepalive;

import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;

/* Visit https://plantuml.com/sequence-diagram for notes on how to draw.
@startuml doc-files/keepalive_reinvite.png
title Keep Alive ReINVITE: Container refreshes RTP streams
hide footbox
participant Alice as alice
participant KeepAlive as blade
participant Bob as bob

alice     <-->      bob : RTP
alice <-  blade         : INVITE
alice --> blade         : 200 OK (SDP)
          blade ->  bob : INVITE (SDP)            
          blade <-- bob : 200 OK (SDP) 
alice <-  blade         : ACK (SDP)

@enduml
*/

/**
 * Handles session expiry by terminating both call legs with BYE requests.
 * Used when a keep-alive timeout occurs and the call should be terminated.
 *
 * @see SessionKeepAlive.Callback
 */
public class KeepAliveExpiry extends ClientCallflow implements SessionKeepAlive.Callback {

	private static final long serialVersionUID = 1L;

	/**
	 * Handles session expiry by sending BYE to both call legs.
	 *
	 * @param sipSession the SIP session that expired
	 */
	@Override
	public void handle(SipSession sipSession) {
		// Defensive null check for sipSession parameter
		if (sipSession == null) {
			return;
		}

		try {
			if (sipSession.isValid()) {
				sendRequest(sipSession.createRequest(BYE));
			}
		} catch (Exception ex) {
			sipLogger.logStackTrace(sipSession, ex);
		}

		SipSession linkedSession = getLinkedSession(sipSession);

		try {
			if (linkedSession != null && linkedSession.isValid()) {
				sendRequest(linkedSession.createRequest(BYE));
			}
		} catch (Exception ex) {
			sipLogger.logStackTrace(linkedSession, ex);
		}
	}
}
