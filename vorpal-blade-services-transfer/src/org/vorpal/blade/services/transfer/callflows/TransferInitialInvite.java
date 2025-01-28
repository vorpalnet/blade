package org.vorpal.blade.services.transfer.callflows;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.InitialInvite;
import org.vorpal.blade.services.transfer.api.v1.Dialog;

public class TransferInitialInvite extends InitialInvite {

	private static final long serialVersionUID = 1L;

	public TransferInitialInvite(B2buaListener b2buaListener) {
		super(b2buaListener);
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		request.getApplicationSession().setAttribute("INITIAL_INVITE", request);

		// saving first initial invite for placing in REST API Dialog object
		request.getSession().setAttribute("initial_invite", request);

		// jwm-test
		Dialog dialog = new Dialog(request.getSession());

		super.process(request);
	}

}
