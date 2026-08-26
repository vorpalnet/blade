package org.vorpal.blade.test.b2bua;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.Callflow;

public class TestB2buaCancel extends Callflow {
	private static final long serialVersionUID = 1L;

	/// Copies an inbound CANCEL and sends it outbound.
	/// Note: The container automatically send 200 OK for CANCELs
	///
	/// @param aliceCancel the inbound CANCEL (from Alice)
	@Override
	public void process(SipServletRequest aliceCancel) throws ServletException, IOException {

		SipServletRequest bobCancel = createCancel(aliceCancel);
		sendRequest(bobCancel);

	}

}
