package org.vorpal.blade.framework.v2.keepalive;

import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SipSession;

import org.vorpal.blade.framework.v2.callflow.ClientCallflow;

/* Visit https://plantuml.com/sequence-diagram for notes on how to draw.
@startuml doc-files/keepalive_expiry.png
title Keep-Alive expiry: no refresh within the session interval
hide footbox
participant Alice as alice
participant KeepAliveExpiry as blade
participant Bob as bob

alice <-  blade          : BYE
alice --> blade          : 200 OK
          blade ->  bob  : BYE
          blade <-- bob  : 200 OK

@enduml
*/

/// Ends a call by hanging up both of its dialogs: the container's expiry callback
/// when no refresh arrived within the session interval, and the keep-alive's own
/// teardown when an endpoint has lost the call.
///
/// @see SessionKeepAlive.Callback
public class KeepAliveExpiry extends ClientCallflow implements SessionKeepAlive.Callback {

	private static final long serialVersionUID = 1L;

	/// Hang up this dialog and the one linked to it.
	///
	/// @param sipSession the dialog whose session expired, or that lost the call
	@Override
	public void handle(SipSession sipSession) {
		if (sipSession == null) {
			return;
		}
		SipSession linkedSession = sipSession.isValid() ? getLinkedSession(sipSession) : null;
		bye(sipSession);
		bye(linkedSession);
	}

	/// Hang up one dialog, unless the container has already ended it. A request
	/// inside a dialog answered 481 or 408 ends that dialog on the spot (RFC 3261
	/// section 12.2.1.2: "the UAC SHOULD terminate the dialog"), and a TERMINATED
	/// dialog cannot create a request; there is nothing left to hang up.
	private void bye(SipSession session) {
		try {
			if (session != null && session.isValid() && session.getState() != SipSession.State.TERMINATED) {
				sendRequest(session.createRequest(BYE));
			}
		} catch (Exception ex) {
			sipLogger.logStackTrace(session, ex);
		}
	}
}
