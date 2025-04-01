package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class RegisterCallflow extends Callflow {
	private static final long serialVersionUID = -5068514646904567798L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipApplicationSession appSession = request.getApplicationSession();

		Registrar registrar = (Registrar) appSession.getAttribute("registrar");
		registrar = (registrar != null) ? registrar : new Registrar();

		SipServletResponse response = registrar.updateContacts(request);
		appSession.setAttribute("registrar", registrar);

		sendResponse(response);
	}

}
