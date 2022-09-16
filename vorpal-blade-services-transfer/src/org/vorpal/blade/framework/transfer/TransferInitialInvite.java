package org.vorpal.blade.framework.transfer;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.b2bua.InitialInvite;

public class TransferInitialInvite extends InitialInvite {

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		request.getApplicationSession().setAttribute("INITIAL_INVITE", request);
		super.process(request);
	}

}
