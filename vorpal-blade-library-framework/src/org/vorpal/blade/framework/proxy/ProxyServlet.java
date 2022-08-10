package org.vorpal.blade.framework.proxy;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callflow;

public abstract class ProxyServlet extends AsyncSipServlet implements ProxyListener {

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		return new ProxyInvite(new ProxyRule(), this);
	}

}
