package org.vorpal.blade.framework.deprecated.proxy;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callflow;

public abstract class ProxyServlet extends AsyncSipServlet implements ProxyListener{



	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow;

		if (request.getMethod().equals("INVITE")) {
			if (request.isInitial()) {
				callflow = new ProxyInvite(this);
			} else {
				callflow = new ProxyReinvite();
			}
		} else if (request.getMethod().equals("BYE")) {
			callflow = new ProxyBye();
		} else if (request.getMethod().equals("CANCEL")) {
			callflow = new ProxyCancel();
		} else {
			callflow = new ProxyPassthru();
		}

		return callflow;
	}

}
