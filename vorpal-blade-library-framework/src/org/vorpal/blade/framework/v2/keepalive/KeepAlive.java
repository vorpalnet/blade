package org.vorpal.blade.framework.v2.keepalive;

import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SipServletRequest;
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

public class KeepAlive extends ClientCallflow implements SessionKeepAlive.Callback {

	private static final long serialVersionUID = 1L;

	@Override
	public void handle(SipSession sipSession) {

		SipSession linkedSession = getLinkedSession(sipSession);

		try {

			if (linkedSession != null) {

				SipServletRequest invite = sipSession.createRequest(INVITE);
				sendRequest(invite, (response) -> {

					if (successful(response)) {
						SipServletRequest linkedInvite = copyContent(response, linkedSession.createRequest(INVITE));
						sendRequest(linkedInvite, (linkedResponse) -> {

							if (successful(linkedResponse)) {
								SipServletRequest aliceAck = copyContent(linkedResponse, response.createAck());
								sendRequest(aliceAck);
								sendRequest(linkedResponse.createAck());
							}

						});
					}

				});

			}

		} catch (Exception ex) {

		}

	}

}
