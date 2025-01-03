package org.vorpal.blade.services.proxy.registrar;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.callflow.Callflow;

public class RegisterCallflow extends Callflow {
	private static final long serialVersionUID = 1L;

	ProxyRegistrar proxyRegistrar;

	private ProxyRegistrarSettings settings;

	public RegisterCallflow(ProxyRegistrarSettings settings) {
		this.settings = settings;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		try {

			SipApplicationSession appSession = request.getApplicationSession();
			ProxyRegistrar proxyRegistrar = (ProxyRegistrar) appSession.getAttribute("PROXY_REGISTRAR");
			if (proxyRegistrar == null) {
				sipLogger.finer(request, "Creating new ProxyRegistrar object...");
			}
			proxyRegistrar = (proxyRegistrar != null) ? proxyRegistrar : new ProxyRegistrar();

			SipServletResponse resp = request.createResponse(200);

			// Echo back the Allow header
			proxyRegistrar.copyAllow(request);
			proxyRegistrar.pasteAllow(resp);
//			resp.setHeader("Allow",
//					"OPTIONS, INVITE, ACK, CANCEL, BYE, REFER, INFO, NOTIFY, UPDATE, MESSAGE, SUBSCRIBE"); // No PRACK

			// Update the Contacts and add to the response
			proxyRegistrar.updateContacts(request);
			List<Address> contacts = proxyRegistrar.getAddressContacts();
			for (Address address : contacts) {
				resp.addAddressHeader("Contact", address, false);
			}

			// Set 'Expires' header if only one contact
			if (contacts.size() == 1) {
				resp.setExpires(Integer.parseInt(contacts.get(0).getParameter("expires")));
			}

			// If no contacts exist,
			if (contacts.isEmpty()) {
				appSession.setInvalidateWhenReady(true);
				if (request.getHeaderList("Contact").isEmpty()) {
					resp = request.createResponse(404);
				}
			} else {
				appSession.setInvalidateWhenReady(false);
				appSession.setExpires((int) Math.ceil(proxyRegistrar.getMaxExpires() / 60.0));
			}

			// Send the response
			sendResponse(resp);

			if (sipLogger.isLoggable(Level.FINER)) {
				sipLogger.finer(request,
						request.getAddressHeader("From").getURI() + " contacts=" + Arrays.toString(contacts.toArray()));
			}

			appSession.setAttribute("PROXY_REGISTRAR", proxyRegistrar);

		} catch (Exception e) {
			sipLogger.severe(request, "Unable to process SIP message:\n" + request.toString());
			sipLogger.logStackTrace(request, e);
			throw e;
		}

	}

}