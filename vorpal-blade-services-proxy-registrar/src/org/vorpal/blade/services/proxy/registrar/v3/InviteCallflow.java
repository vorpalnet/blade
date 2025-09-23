package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;

public class InviteCallflow extends Callflow implements Serializable {
	private static final long serialVersionUID = 397213565821542521L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipApplicationSession appSession = sipUtil
				.getApplicationSessionByKey(AsyncSipServlet.getAccountName(request.getRequestURI()), false);

		Callflow.getSipLogger().severe(request, "InviteCallflow.process - appSession=" + appSession);

		List<URI> contacts = null;

		Settings settings = PRServlet.settingsManager.getCurrent();
		Proxy proxy = request.getProxy();
		settings.apply(proxy);

		if (appSession != null) {
			Registrar registrar = (Registrar) appSession.getAttribute("registrar");
			Callflow.getSipLogger().severe(request, "InviteCallflow.process - registrar=" + registrar);
			if (registrar != null) {
				contacts = registrar.getContacts(request);
			}
		}

		if ((contacts == null || contacts.size() == 0) && settings.proxyOnUnregistered) {
			contacts = new LinkedList<>();
			contacts.add(request.getRequestURI());
		}

		if (contacts != null) {
			// Proxy on, dudes!
			proxyRequest(proxy, contacts);
		} else {
			// Only true wisdom consists in knowing that you know nothing.
			sendResponse(request.createResponse(404));
		}

	}

}
