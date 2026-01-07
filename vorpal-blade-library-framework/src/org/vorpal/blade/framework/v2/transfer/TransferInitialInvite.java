package org.vorpal.blade.framework.v2.transfer;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.InitialInvite;

/**
 * Handles initial INVITE requests in a transfer-capable B2BUA.
 *
 * <p>Stores the initial INVITE for later use by transfer operations
 * and REST API dialog inspection.
 */
public class TransferInitialInvite extends InitialInvite {

	private static final long serialVersionUID = 1L;

	// Session attribute keys
	private static final String INITIAL_INVITE_APP_SESSION_ATTR = "INITIAL_INVITE";
	private static final String INITIAL_INVITE_SESSION_ATTR = "initial_invite";

	public TransferInitialInvite(B2buaListener b2buaListener) {
		super(b2buaListener);
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		request.getApplicationSession().setAttribute(INITIAL_INVITE_APP_SESSION_ATTR, request);

		// saving first initial invite for placing in REST API Dialog object
		request.getSession().setAttribute(INITIAL_INVITE_SESSION_ATTR, request);

		super.process(request);
	}

}
