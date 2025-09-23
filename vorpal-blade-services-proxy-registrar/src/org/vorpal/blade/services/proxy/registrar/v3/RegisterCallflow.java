package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.IOException;
import java.io.Serializable;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class RegisterCallflow extends Callflow implements Serializable {
	private static final long serialVersionUID = -5068514646904567798L;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {
		SipApplicationSession appSession = request.getApplicationSession();

		SipServletResponse response;

		try {
			Registrar registrar = (Registrar) appSession.getAttribute("registrar");
			registrar = (registrar != null) ? registrar : new Registrar();

			response = registrar.updateContacts(request);
			appSession.setAttribute("registrar", registrar);

		} catch (Exception ex) {
			// Something is corrupt, clear memory, have user-agent try again
			response = request.createResponse(503);
			appSession.removeAttribute("registrar");
		}
		sendResponse(response);

	}

}
