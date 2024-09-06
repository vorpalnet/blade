package org.vorpal.blade.framework.transfer;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.b2bua.B2buaListener;
import org.vorpal.blade.framework.b2bua.InitialInvite;

public class TransferInitialInvite extends InitialInvite {

	private static final long serialVersionUID = 1L;

	public TransferInitialInvite(B2buaListener b2buaListener) {
		super(b2buaListener);
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		request.getApplicationSession().setAttribute("INITIAL_INVITE", request);
		super.process(request);
	}

}
