package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;

public class InviteCallflow extends Callflow {
	private static final long serialVersionUID = 397213565821542521L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipApplicationSession appSession = sipUtil
				.getApplicationSessionByKey(AsyncSipServlet.getAccountName(request.getRequestURI()), false);

		Settings settings = PRServlet.settingsManager.getCurrent();
		Proxy proxy = request.getProxy();
		settings.apply(proxy);

		Registrar registrar = (Registrar) appSession.getAttribute("registrar");
		if (registrar != null) {
			List<URI> contacts = registrar.getContacts(request);
			if (contacts != null && contacts.size() > 0) {
				this.proxyRequest(proxy, contacts);
			} else {
				if (settings.proxyOnUnregistered) {
					sipLogger.finer(request, "proxy.proxyTo(" + request.getRequestURI() + ");");
					List<URI> requestUriContacts = new LinkedList<>();
					requestUriContacts.add(request.getRequestURI());
					this.proxyRequest(proxy, requestUriContacts);

					proxy.proxyTo(request.getRequestURI());
				} else {
					sipLogger.finer(request, "sendResponse(request.createResponse(404));");
					sendResponse(request.createResponse(404));
				}
			}
		} else {
			if (settings.proxyOnUnregistered) {
				sipLogger.finer(request, "proxy.proxyTo(" + request.getRequestURI() + ");");

				proxy.proxyTo(request.getRequestURI());
			} else {
				sipLogger.finer(request, "sendResponse(request.createResponse(404));");
				sendResponse(request.createResponse(404));
			}
		}

	}

}
