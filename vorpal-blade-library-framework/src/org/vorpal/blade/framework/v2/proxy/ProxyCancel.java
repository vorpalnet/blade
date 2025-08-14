package org.vorpal.blade.framework.v2.proxy;

import java.io.IOException;
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class ProxyCancel extends Callflow implements Serializable {
	private ProxyListener proxyListener;

	public ProxyCancel(ProxyListener proxyListener) {
		this.proxyListener = proxyListener;
	}

	private static final long serialVersionUID = 1L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		sendResponse(request.createResponse(200));

		if (this.proxyListener != null) {
			// what happens if there's a CANCEL?
		}

		request.getProxy().cancel();

	}

}
