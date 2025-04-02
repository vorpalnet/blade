package org.vorpal.blade.services.transfer.api.v1;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.callflow.ClientCallflow;

public class InitiateReinvite extends ClientCallflow {
	private Address alice;
	private Address bob;
	private B2buaListener b2buaListener;

	private static final long serialVersionUID = 4666101941210077193L;

	public InitiateReinvite(B2buaListener b2buaListener, Address from, Address to) {
		this.alice = from;
		this.bob = to;
		this.b2buaListener = b2buaListener;
	}

	public InitiateReinvite(B2buaListener b2buaListener, String from, String to) throws ServletParseException {
		this.alice = sipFactory.createAddress(from);
		this.bob = sipFactory.createAddress(to);
		this.b2buaListener = b2buaListener;

	}

	public void process() throws ServletException, IOException {
		SipApplicationSession appSession = sipFactory.createApplicationSession();

		SipServletRequest aliceRequest = sipFactory.createRequest(appSession, INVITE, bob, alice);
		aliceRequest.setContent(blackhole, "application/sdp");

		SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, alice, bob);
		bobRequest.setContent(blackhole, "application/sdp");

		AsyncSipServlet.generateIndexKey(aliceRequest);

		sendRequest(aliceRequest, (aliceResponse) -> {

			if (successful(aliceResponse)) {
				sendRequest(aliceResponse.createAck());

				sendRequest(bobRequest, (bobResponse) -> {

					if (successful(bobResponse)) {
						sendRequest(bobResponse.createAck());
						// now connect the two
						sendRequest(aliceResponse.getSession().createRequest(INVITE), (aliceResponse2) -> {
							sendRequest(copyContent(aliceResponse2, bobResponse.getSession().createRequest(INVITE)),
									(bobResponse2) -> {
										sendRequest(copyContent(bobResponse2, aliceResponse2.createAck()));
										sendRequest(bobResponse2.createAck());
									});
						});
					} else if (failure(bobResponse)) {
						// bob couldn't answer
						sendRequest(aliceResponse.getSession().createRequest("BYE"));
					}

				});

			}

		});



	}

	static final String blackhole = "" + //
			"v=0\r\n" + //
			"o=- 15474517 1 IN IP4 127.0.0.1\r\n" + //
			"s=cpc_med\r\n" + //
			"c=IN IP4 0.0.0.0\r\n" + //
			"t=0 0\r\n" + //
			"m=audio 23348 RTP/AVP 0\r\n" + //
			"a=rtpmap:0 pcmu/8000\r\n" + //
			"a=inactive\r\n";
}
