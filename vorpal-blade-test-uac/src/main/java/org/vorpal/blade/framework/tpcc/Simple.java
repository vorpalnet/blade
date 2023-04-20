package org.vorpal.blade.framework.tpcc;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.test.client.Header;


/**
 * This is a simple Third-Party Call-Control callflow.
 * 
 * <pre>{@code
 * 
 *   Alice               BLADE                Bob
 * ----------           --------             -----
 *     |                   |                   |
 *     |            INVITE |                   |
 *     |<-----(blackhole)--|                   |
 *     | 180 Ringing       |                   |
 *     |------------------>|                   |
 *     | 200 OK            |                   |
 *     |--(sdp)----------->|                   |
 *     |               ACK |                   |
 *     |<------------------|                   |
 *     |==(silence)========|                   |
 *     |          reINVITE |                   |
 *     |<------------------|                   |
 *     | 200 OK            |                   |
 *     |--(sdp)----------->|                   |
 *     |                   | INVITE            |
 *     |                   |--(sdp)----------->|
 *     |                   |       180 Ringing |
 *     |                   |<------------------|
 *     |                   |            200 OK |
 *     |                   |                   |
 *     |                   |                   |
 *     |                   | INVITE            |
 *     |                   |--(sdp)----------->|
 *     |                   |                   |
 *     |                   |                   |
 *     |                   |                   |
 *     |                   |                   |
 *     |                   |                   |
 *     |                   |                   |
 *     |                   |                   |
 *     |                   |                   |
 *     | INVITE            |                   |
 *     |--(sdp)----------->|                   |
 *     |                   | INVITE            |
 *     |                   |--(sdp)----------->|
 *     |                   |            200 OK |
 *     |                   |<-----------(sdp)--|
 *     |            200 OK |                   |
 *     |<-----------(sdp)--|                   |
 *     | ACK               |                   |
 *     |------------------>|                   |
 *     |                   | ACK               |
 *     |                   |------------------>|
 *     |                   |                   |
 *       
 * }</pre>
 */
public class Simple extends Callflow {
	private static final long serialVersionUID = 1L;
	private Address alice;
	private Address bob;
	private URI requestURI;
	private List<Header> headers;
	private String contentType;
	private String content;
	private String appSessionId;
	private String fromSessionId;
	private String toSessionId;
	

	public Simple(Address alice, Address bob) {
		this.alice = alice;
		this.bob = bob;

	}
	
	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		// not implemented;
	}

	public void process() throws ServletException, IOException {
		SipApplicationSession appSession = sipFactory.createApplicationSession();

		SipServletRequest aliceRequest = sipFactory.createRequest(appSession, INVITE, bob, alice);
		SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, alice, bob);
		
		sendRequest(aliceRequest, (aliceResponse)->{
			
			if(this.successful(aliceResponse)) {
				
//				sendRequest
				
				
				
			}
			
			
			
		});
		
		
		
		
		
	}



	
	
	
}
