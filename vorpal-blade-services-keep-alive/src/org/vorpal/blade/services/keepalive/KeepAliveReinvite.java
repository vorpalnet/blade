package org.vorpal.blade.services.keepalive;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SessionKeepAlive;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;

/**
 * This class manages keep-alive requests by using re-INVITEs.
 * 
 * @author Jeff McDonald
 */
public class KeepAliveReinvite extends KeepAliveCallflow {

	private static final long serialVersionUID = 1L;

	/**
	 * If Alice calls Bob, this method sends and empty reINVITE to Bob. Bob replies
	 * back with his SDP. This method will then send a reINVITE to Alice with Bob's
	 * SDP. Alice replies back with her SDP. Then it's ACKs all around and the tea party resumes.
	 */
	@Override
	public void handle(SipSession sipSession) {

		try {
			SipServletRequest bobRequest = sipSession.createRequest(INVITE);

			sendRequest(bobRequest, (bobResponse) -> {
				if (successful(bobResponse)) {
					SipServletRequest aliceRequest = getLinkedSession(bobResponse.getSession()).createRequest(INVITE);
					copyContent(bobResponse, aliceRequest);
					sendRequest(aliceRequest, (aliceResponse) -> {
						SipServletRequest aliceAck = aliceResponse.createAck();
						sendRequest(aliceAck);

						SipServletRequest bobAck = bobResponse.createAck();
						copyContent(aliceResponse, bobAck);
						sendRequest(bobAck);
					});
				}
			});
		} catch (ServletException | IOException e) {
			sipLogger.logStackTrace(e);
		}

	}

}
