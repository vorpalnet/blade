package org.vorpal.blade.services.proxy.registrar;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.callflow.Callflow;

public class ProxyCallflow extends Callflow {

	private ProxyRegistrarSettings settings;

	public ProxyCallflow(ProxyRegistrarSettings settings) {
		this.settings = settings;
	}

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		try {

			if (request.isInitial()) {

				SipApplicationSession appSession = request.getApplicationSession();
				appSession.setInvalidateWhenReady(false);

				ProxyRegistrar proxyRegistrar = (ProxyRegistrar) appSession.getAttribute("PROXY_REGISTRAR");

//				if (proxyRegistrar == null) {
//					sipLogger.severe(request, "No ProxyRegistrar found for appSession: " + appSession.getId());
//				}

				proxyRegistrar = (proxyRegistrar != null) ? proxyRegistrar : new ProxyRegistrar();

				List<URI> contacts = proxyRegistrar.getURIContacts();
				if (sipLogger.isLoggable(Level.FINER)) {
					sipLogger.finer(request,
							"Contacts for " + request.getTo().getURI() + ": " + Arrays.toString(contacts.toArray()));
				}

				if (contacts.isEmpty()) {

					if (settings.isProxyOnUnregistered()) {

						if (sipLogger.isLoggable(Level.FINER)) {
							sipLogger.finer(request,
									"Account " + request.getTo() + " not registered. Proxying anyway.");
						}
						request.getProxy().proxyTo(request.getTo().getURI());
					} else {
						sendResponse(request.createResponse(404));
					}

				} else {

//					LinkedList<URI> aors = new LinkedList<URI>();
//					for (URI uri : contacts) {
//						aors.add( copyParameters(request.getRequestURI(), uri) );
//					}

					Proxy proxy = request.getProxy();

// defaults
//					proxy.setAddToPath(false); // Whether the application adds a Path header to the REGISTER request.
//					proxy.setRecurse(true); // Whether to automatically recurse or not.
//					proxy.setRecordRoute(false); // Whether to record-route or not.
//					proxy.setParallel(true); // Whether to proxy in parallel or sequentially.
//					proxy.setStateful(true); // Whether to remain transaction stateful for the duration of the proxying operation.
//					proxy.setSupervised(false); // Whether the application will be invoked on incoming responses related to this proxying.

					
					proxy.setRecordRoute(false); // Whether to record-route or not.
					proxy.setSupervised(false); // Whether the application will be invoked on incoming responses related to this proxying.
					
					
					
//					proxy.setProxyTimeout(settings.getProxyTimeout());

					sipLogger.warning(request, "Proxying to: " + Arrays.toString(contacts.toArray()));

//					proxy.createProxyBranches(contacts);
//					proxy.startProxy();
					proxy.proxyTo(contacts);

				}

				appSession.setAttribute("PROXY_REGISTRAR", proxyRegistrar);

			} else {
				sipLogger.severe(request, "Logical error... INVITE not marked at 'initial'.");
			}

		} catch (Exception e) {
			sipLogger.logStackTrace(request, e);
			throw e;
		}

	}

}
