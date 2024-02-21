package org.vorpal.blade.framework.proxy;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callflow;

public abstract class ProxyServlet extends AsyncSipServlet implements ProxyListener {

	private static final long serialVersionUID = 1L;

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;

		if (request.getMethod().equals("INVITE") && request.isInitial()) {
			callflow = new ProxyInvite(this, null);
		}

		return callflow;
	}

}
