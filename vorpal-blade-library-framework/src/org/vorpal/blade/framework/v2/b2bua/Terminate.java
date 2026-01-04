package org.vorpal.blade.framework.v2.b2bua;

import java.io.IOException;
import java.io.Serializable;
import java.util.Iterator;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class Terminate extends Callflow implements Serializable {
	private static final long serialVersionUID = 1L;
	B2buaListener b2buaListener;

	public Terminate(B2buaListener b2buaListener) {
		this.b2buaListener = b2buaListener;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// request can be either a BYE or CANCEL

		SipApplicationSession appSession = request.getApplicationSession();
		SipSession sipSession = request.getSession();
		SipSession linkedSession;
		Exception exception = null;

		// Send response immediately for fear of a downstream process eating the BYE
		if (request.getMethod().equals(BYE)) {
			sendResponse(request.createResponse(200));
		}

		@SuppressWarnings("unchecked")
		Iterator<SipSession> itr = (Iterator<SipSession>) appSession.getSessions(SIP);
		while (itr.hasNext()) { // iterate through all possible linked sessions
			linkedSession = itr.next();

			if (linkedSession != sipSession) { // do not operate on self
				if (getLinkedSession(linkedSession) == sipSession) { // we found it!
					SipServletRequest terminationRequest = null;

					if (sipLogger.isLoggable(Level.FINEST)) {
						sipLogger.finest(request, "ByeOrCancel.process - sipSession.id=" + sipSession.getId() //
								+ ", sipSession.state=" + sipSession.getState() //
								+ ", sipSession.isValid=" + sipSession.isValid() //
								+ ", linkedSession.id=" + linkedSession.getId() //
								+ ", linkedSession.state=" + linkedSession.getState() //
								+ ", linkedSession.isValid=" + linkedSession.isValid() //
						);
					}

					switch (linkedSession.getState()) {

					case INITIAL: // has not been sent?
						linkedSession.invalidate();
						break;

					case EARLY: // 180 Ringing, sipSession will be in INITIAL state
						terminationRequest = linkedSession.getActiveInvite(UAMode.UAC).createCancel();
						copyContentAndHeaders(request, terminationRequest);

						if (b2buaListener != null) {
							b2buaListener.callAbandoned(terminationRequest);
						}

						break;

					case CONFIRMED: // 200 OK
						terminationRequest = linkedSession.createRequest(BYE);
						copyContentAndHeaders(request, terminationRequest);

						if (b2buaListener != null) {
							b2buaListener.callCompleted(terminationRequest);
						}

						break;

					case TERMINATED: // two simultaneous BYEs from both directions?
						// nothing you can do
						break;
					}

					try {
						if (terminationRequest != null) {
							sendRequest(terminationRequest);
						}
					} catch (Exception ex1) {
						exception = ex1;
					}

				}

			}

		}

		if (exception != null) {
			throw new ServletException(exception);
		}

	}

}
