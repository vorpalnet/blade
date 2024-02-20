package org.vorpal.blade.framework.transfer;

import java.io.IOException;
import java.util.Iterator;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;

import org.vorpal.blade.framework.callflow.Callflow;

public class TransferCancel extends Callflow {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipApplicationSession appSession = request.getApplicationSession();
		SipSession sipSession = request.getSession();

		Iterator<SipSession> itr = (Iterator<SipSession>) appSession.getSessions();

		SipSession ss;
		while (itr.hasNext()) {

			ss = itr.next();

			if (sipSession.equals(ss)) {
				// do nothing;
			} else {

				if (0 == ss.getState().compareTo(State.CONFIRMED)) {
					sendRequest(ss.createRequest(BYE));
				} else {
					sendRequest(ss.createRequest(CANCEL));
				}
			}
		}

	}

}
