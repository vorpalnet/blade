/**
 *  MIT License
 *  
 *  Copyright (c) 2016 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package org.vorpal.blade.services.proxyregistrar;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.sip.Address;
import javax.servlet.sip.Proxy;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;
import javax.servlet.sip.annotation.Invite;
import javax.servlet.sip.annotation.Register;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;

@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyRegistrarServlet implements SipServletListener {
	private static Logger sipLogger;

	@Inject
	private ProxyRegistrar proxyRegistrar;

//	private static ProxyRegistrarSettings settings = null;
	private static SettingsManager<ProxyRegistrarSettings> settingsManager;

	@Resource
	public static SipFactory sipFactory;

	@Resource
	public static SipSessionsUtil sipUtil;

	@SipApplicationKey
	public static String sessionKey(SipServletRequest req) {
		String key = null;
		key = getAccountName(req.getTo());

		sipLogger.fine("Returning sessionKey: " + key);

		return key;
	}

	@Override
	public void servletInitialized(SipServletContextEvent event) {
		sipLogger = LogManager.getLogger(event.getServletContext());
		Callflow.setLogger(sipLogger);
		settingsManager = new SettingsManager<>(event, ProxyRegistrarSettings.class);
	}

	@Register
	public void doRegister(SipServletRequest req) throws ServletException, IOException {

		sipLogger.fine("doRegister... proxyRegistrar: " + proxyRegistrar);

//		ProxyRegistrarSettings settings = (ProxyRegistrarSettings) ConfigurationManager.loadConfiguration(ProxyRegistrarSettings.class);

//		ProxyRegistrarSettings settings = ConfigurationManager.getConfiguration()

		try {

			// jwm -- bad
//			proxyRegistrar = (ProxyRegistrar) req.getApplicationSession().getAttribute("PROXY_REGISTRAR");
//			if (proxyRegistrar == null) {
//				proxyRegistrar = new ProxyRegistrar();
//			}

//			if (logger.isLoggable(Level.FINE)) {
//				String contacts = Arrays.toString(proxyRegistrar.getContacts().toArray());
//				logger.log(Level.FINE, Direction.RECEIVE, req);
//			}
			SipApplicationSession appSession = req.getApplicationSession();
			SipServletResponse resp = req.createResponse(200);

			proxyRegistrar.updateContacts(req);
			List<Address> contacts = proxyRegistrar.getContacts();

			// Add contacts to response
			for (Address contact : contacts) {
				resp.addAddressHeader("Contact", contact, false);
			}

			// Set 'Expires' header if only one contact
			if (contacts.size() == 1) {
				resp.setExpires(Integer.parseInt(contacts.get(0).getParameter("expires")));
			}

			if (contacts.isEmpty()) {
				appSession.setInvalidateWhenReady(true);

				if (req.getHeaderList("Contact").isEmpty()) {
					// Query for contact and none found
					resp = req.createResponse(404);
				}

			} else {
				appSession.setInvalidateWhenReady(false);
				appSession.setExpires((int) Math.ceil(proxyRegistrar.getMaxExpires() / 60.0));
			}

			resp.setHeader("Allow", "INVITE,ACK,BYE,CANCEL,OPTIONS,REGISTER,PRACK,SUBSCRIBE,NOTIFY,PUBLISH,INFO,REFER,MESSAGE,UPDATE");

			resp.send();

			if (sipLogger.isLoggable(Level.FINE)) {
				sipLogger.fine(req.getFrom() + " contacts=" + Arrays.toString(proxyRegistrar.getContacts().toArray()));
			}

			// jwm-bad
			// req.getApplicationSession().setAttribute("PROXY_REGISTRAR", proxyRegistrar);

		} catch (Exception e) {
			sipLogger.log(Level.SEVERE, e.getMessage(), e);
			throw e;
		}

	}

	@Invite
	public void doInvite(SipServletRequest req) throws ServletException, IOException {
		sipLogger.fine("ProxyRegistrarServlet.doInvite...");

		ProxyRegistrarSettings settings = settingsManager.getCurrent();

		if (req.isInitial()) {

			SipApplicationSession regAppSession = sipUtil.getApplicationSessionByKey(getAccountName(req.getTo()), false);

			List<Address> contacts = proxyRegistrar.getContacts();
			sipLogger.fine("contacts: " + contacts);

			sipLogger.fine(req.getTo().getURI() + ", " + Arrays.toString(contacts.toArray()));

			if (contacts.isEmpty()) {

				if (settings.isProxyOnUnregistered()) {
					sipLogger.fine("Error. " + req.getTo() + " not registered. Proxying anyway.");
					req.getProxy().proxyTo(req.getTo().getURI());
				} else {
					sipLogger.fine("Error 404. " + req.getTo() + " not registered. ");
					req.createResponse(404).send();
				}

			} else {
				LinkedList<URI> aors = new LinkedList<URI>();
				for (Address address : contacts) {
					aors.add(address.getURI());
				}

				Proxy proxy = req.getProxy();

				proxy.setAddToPath(settings.isAddToPath());
				proxy.setNoCancel(settings.isNoCancel());
				proxy.setParallel(settings.isParallel());
				proxy.setProxyTimeout(settings.getProxyTimeout());
				proxy.setRecordRoute(settings.isRecordRoute());
				proxy.setRecurse(settings.isRecurse());
//				proxy.setSupervised(settings.isSupervised());

				proxy.createProxyBranches(aors);
				proxy.startProxy();

				if (sipLogger.isLoggable(Level.FINE)) {
					sipLogger.fine("Proxying " + req.getTo() + " to: " + Arrays.toString(contacts.toArray()));
				}
			}
		}

	}
	
	public static String getAccountName(Address address) {
		return getAccountName(address.getURI());
	}

	public static String getAccountName(URI _uri) {
		SipURI sipUri = (SipURI) _uri;
		return sipUri.getUser().toLowerCase() + "@" + sipUri.getHost().toLowerCase();
	}

}
