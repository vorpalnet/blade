package org.vorpal.blade.test.b2bua;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v3.Callflow;

public class TestB2buaPassthru extends Callflow {
	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest aliceRequest) throws ServletException, IOException {

		sipLogger.finer(aliceRequest, "Received " + aliceRequest.getMethod() + " from Alice.");

		SipServletRequest bobRequest = createRequest(aliceRequest);

		sipLogger.finer(bobRequest, "Sending " + bobRequest.getMethod() + " to Bob.");

		sendRequest(bobRequest, (bobResponse) -> {

			if (provisional(bobResponse)) {
				sipLogger.finer(bobResponse, "Received provisional " + bobResponse.getStatus() + " "
						+ bobResponse.getReasonPhrase() + " response from Bob.");

			} else if (successful(bobResponse)) {
				sipLogger.finer(bobResponse, "Received successful " + bobResponse.getStatus() + " "
						+ bobResponse.getReasonPhrase() + " response from Bob.");

			} else if (failure(bobResponse)) {
				sipLogger.finer(bobResponse, "Received failure " + bobResponse.getStatus() + " "
						+ bobResponse.getReasonPhrase() + " response from Bob.");
			}

			SipServletResponse aliceResponse = createResponse(aliceRequest, bobResponse);

			sipLogger.finer(aliceResponse, "Sending " + aliceResponse.getStatus() + " "
					+ aliceResponse.getReasonPhrase() + " response to Alice.");

			sendResponse(aliceResponse);

		});

	}

}
