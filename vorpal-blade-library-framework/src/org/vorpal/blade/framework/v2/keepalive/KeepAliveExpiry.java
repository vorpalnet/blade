package org.vorpal.blade.framework.v2.keepalive;

import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;
import org.vorpal.blade.framework.v2.config.KeepAliveParameters.KeepAlive;

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

public class KeepAliveExpiry extends ClientCallflow implements SessionKeepAlive.Callback {

	private static final long serialVersionUID = 1L;

	@Override
	public void handle(SipSession sipSession) {

		try {
			if (sipSession != null && sipSession.isValid()) {
				sendRequest(sipSession.createRequest(BYE));
			}
		} catch (Exception ex1) {
			sipLogger.logStackTrace(sipSession, ex1);
		}

		SipSession linkedSession = getLinkedSession(sipSession);

		try {
			if (linkedSession != null && linkedSession.isValid()) {
				sendRequest(linkedSession.createRequest(BYE));
			}
		} catch (Exception ex1) {
			sipLogger.logStackTrace(linkedSession, ex1);
		}

	}

}
