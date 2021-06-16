// Copyright (c) 2016 VORPAL.ORG

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
import javax.servlet.sip.URI;
import javax.servlet.sip.annotation.Invite;
import javax.servlet.sip.annotation.Register;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.Settings;
import org.vorpal.blade.framework.config.SipUtil;
import org.vorpal.blade.framework.logging.LogManager;
import org.vorpal.blade.framework.logging.Logger;

@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyRegistrarServlet implements SipServletListener {
	private static Logger logger;

	@Inject
	private ProxyRegistrar proxyRegistrar;

	private static ProxyRegistrarSettings settings = null;

	@Resource
	public static SipFactory sipFactory;

	@Resource
	public static SipSessionsUtil sipUtil;

	@SipApplicationKey
	public static String sessionKey(SipServletRequest req) {
		String key = null;
//		if (req.getMethod().equals("REGISTER")) {
		key = SipUtil.getAccountName(req.getTo());
//		}

		logger.fine("Returning sessionKey: " + key);

		return key;
	}

	@Register
	public void doRegister(SipServletRequest req) throws ServletException, IOException {

		logger.fine("doRegister... proxyRegistrar: " + proxyRegistrar);

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

			if (logger.isLoggable(Level.FINE)) {
				logger.fine(req.getFrom() + " contacts=" + Arrays.toString(proxyRegistrar.getContacts().toArray()));
			}

			// jwm-bad
			// req.getApplicationSession().setAttribute("PROXY_REGISTRAR", proxyRegistrar);

		} catch (Exception e) {
			logger.log(Level.SEVERE, e.getMessage(), e);
			throw e;
		}

	}

	@Invite
	public void doInvite(SipServletRequest req) throws ServletException, IOException {
//		ProxyRegistrarSettings settings = (ProxyRegistrarSettings) ConfigurationManager.getConfiguration();

//		logger.fine("------------INVITE-----------------");
		logger.severe("ProxyRegistrarServlet.doInvite...");

//		logger.log(Level.FINE, Direction.RECEIVE, req);

		SipApplicationSession regAppSession = sipUtil.getApplicationSessionByKey(SipUtil.getAccountName(req.getTo()), false);
//jwm-needed?
//		if (regAppSession == null) {
//			proxyRegistrar = new ProxyRegistrar();
//		} else {
//			proxyRegistrar = (ProxyRegistrar) regAppSession.getAttribute("PROXY_REGISTRAR");
//		}

		List<Address> contacts = proxyRegistrar.getContacts();
		logger.fine("contacts: " + contacts);

		logger.fine(req.getTo().getURI() + ", " + Arrays.toString(contacts.toArray()));

		if (contacts.isEmpty()) {

			if (settings.isProxyOnUnregistered()) {
				logger.fine("Error. " + req.getTo() + " not registered. Proxying anyway.");
				req.getProxy().proxyTo(req.getTo().getURI());
			} else {
				logger.fine("Error 404. " + req.getTo() + " not registered. ");
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

			proxy.createProxyBranches(aors);
			proxy.startProxy();

			if (logger.isLoggable(Level.FINE)) {
				logger.fine("Proxying " + req.getTo() + " to: " + Arrays.toString(contacts.toArray()));
			}
		}

	}

	@Override
	public void servletInitialized(SipServletContextEvent event) {

		Settings settingsManager = new Settings(event);

		try {
			settings = (ProxyRegistrarSettings) settingsManager.load(ProxyRegistrarSettings.class);
		} catch (Exception ex) {
			Callflow.sipLogger.logStackTrace(ex);
			ex.printStackTrace();
		}

		if (settings == null) {
			settings = new ProxyRegistrarSettings();

			try {
				settingsManager.save(settings);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		logger = LogManager.getLogger(event.getServletContext());
		logger.logConfiguration(settings);
	}

}
