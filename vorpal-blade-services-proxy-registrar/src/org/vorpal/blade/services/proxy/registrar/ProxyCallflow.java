package org.vorpal.blade.services.proxy.registrar;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.logging.ConsoleColors;
import org.vorpal.blade.framework.v2.logging.Logger.Direction;
import org.vorpal.blade.framework.v2.proxy.ProxyListener;
import org.vorpal.blade.framework.v2.proxy.ProxyPlan;

public class ProxyCallflow extends Callflow {

	private static final long serialVersionUID = -7048001724524467716L;
	private ProxyListener proxyListener;
	private ProxyPlan proxyPlan;
	private Boolean proxyOnUnregistered = false;

	public ProxyCallflow(ProxyListener proxyListener, ProxyPlan proxyPlan, Boolean proxyOnUnregistered) {

		if (proxyPlan != null) {
			// need a deep copy to manipulate the object without fouling up the settings
			this.proxyPlan = new ProxyPlan(proxyPlan);
		}

		if (proxyListener != null) {
			this.proxyListener = proxyListener;
		}

		if (proxyOnUnregistered != null) {
			this.proxyOnUnregistered = proxyOnUnregistered;
		}
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		try {

			if (request.isInitial()) {

				SipApplicationSession appSession = request.getApplicationSession();
				appSession.setInvalidateWhenReady(false);

				ProxyRegistrar proxyRegistrar = (ProxyRegistrar) appSession.getAttribute("PROXY_REGISTRAR");

				proxyRegistrar = (proxyRegistrar != null) ? proxyRegistrar : new ProxyRegistrar();

				List<URI> contacts = proxyRegistrar.getURIContacts();
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request,
							"Contacts for " + request.getTo().getURI() + ": " + Arrays.toString(contacts.toArray()));
				}

				if (contacts.isEmpty()) {

					if (this.proxyOnUnregistered == true) {

						if (sipLogger.isLoggable(Level.FINER)) {
							sipLogger.finer(request,
									"Account " + request.getTo() + " not registered. Proxying anyway.");
						}
						request.getProxy().proxyTo(request.getTo().getURI());
					} else {
						sendResponse(request.createResponse(404));
					}

				} else {
					sipLogger.warning(request, "Proxying to: " + Arrays.toString(contacts.toArray()));

					Proxy proxy = request.getProxy();
					proxy.proxyTo(contacts);
				}

				appSession.setAttribute("PROXY_REGISTRAR", proxyRegistrar);

			} else { // not initial

				// for proxy
				if (request.getProxy().getProxyBranches().size() > 0) {
					sipLogger.warning(ConsoleColors.RED+"ProxyCallflow, line 88.");
					Callflow.getLogger().superArrow(Direction.SEND, false, request, null,
							this.getClass().getSimpleName(), null);
					sipLogger.warning(ConsoleColors.RESET);
				}

			}

		} catch (Exception e) {
			sipLogger.severe(request, "Unable to process SIP message: \n" + request.toString());
			sipLogger.logStackTrace(request, e);
			throw e;
		}

	}

}
