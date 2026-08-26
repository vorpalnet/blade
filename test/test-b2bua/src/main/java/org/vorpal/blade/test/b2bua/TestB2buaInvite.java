package org.vorpal.blade.test.b2bua;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v3.Callflow;

public class TestB2buaInvite extends Callflow {
	private static final long serialVersionUID = 1L;

	/// public void process(SipServletRequest aliceRequest) throws...
	///
	/// The AsyncSipServlet, after calling it's chooseCallflow() method and
	/// discovering that this class 'TestB2buaInvite' is the correct Callflow,
	/// invokes the process() method.
	///
	/// ...SipServletRequest bobRequest = createRequest(aliceRequest);
	///
	/// The createRequest() method either calls sipFactory.createRequest() or
	/// SipSession.createRequest() depending upon whether the aliceRequest object's
	/// SipSession is 'linked'. For the initial invite the session is not linked.
	/// For re-INVITEs, the session is linked. If the sessions were not linked, they
	/// are now! Actually, it links the inbound session (Alice) to the outgoing
	/// session (Bob). It also copies the content and headers.
	///
	/// ......sendRequest(bobRequest, (bobResponse) -> {
	///
	/// Sends the request to Bob and waits asynchronously for a response, which
	/// may be received as a 180 Ringing, 200 OK, or some error code like 404 Not
	/// Found. Since multiple response codes may be delivered, the code inside the
	/// 'sendRequest' lambda expression may run multiple times. So, createResponse()
	/// may get called twice! This is where you might want to put some conditional
	/// logic.
	///
	/// .........SipServletResponse aliceResponse = createResponse(aliceRequest,...
	///
	/// Creates a SipServletResponse and copies the content and headers. It also
	/// links the SipSessions. Now the sessions are bi-directionally linked. Why
	/// didn't sendRequest link the sessions bi-directionally at first? You may want
	/// to send out multiple simultaneous requests, but you can only send one
	/// response back. That's the session to be linked. The others will be thrown
	/// away.
	///
	/// .........sendResponse(aliceResponse, (aliceAck) -> {
	///
	/// Sends a response back to Alice and waits asynchronously for an
	/// acknowledgement (ACK or PRACK). If your network supports PRACK, this method
	/// may get called twice as well.
	///
	/// ............SipServletRequest ackOrPrack =
	/// createAcknowledgement(bobResponse, aliceAck);
	///
	/// Creates the correct type of acknowledgement, ACK or PRACK, and copies
	/// content and headers.
	///
	/// ............sendAcknowledgement(ackOrPrack, bobResponse);
	///
	/// Sends the acknowledgement. If PRACK is enabled in the network, this method
	/// may be called twice, once for PRACK and again for ACK.
	///
	/// @param aliceRequest the inbound message (from Alice)
	@Override
	public void process(SipServletRequest aliceRequest) throws ServletException, IOException {

		SipServletRequest bobRequest = createRequest(aliceRequest);
		sendRequest(bobRequest, (bobResponse) -> {
			
			SipServletResponse aliceResponse = createResponse(aliceRequest, bobResponse);
			sendResponse(aliceResponse, (aliceAck) -> {
				
				SipServletRequest bobAckOrPrack = createAcknowledgement(bobResponse, aliceAck);
				sendAcknowledgement(bobAckOrPrack, bobResponse);
			});

		});

	}

}
