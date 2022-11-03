package org.vorpal.blade.services.proxy.registrar;

import java.io.IOException;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.AsyncSipServlet;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.ConsoleColors;
import org.vorpal.blade.framework.logging.Logger;

/**
 * This class implements an example B2BUA with transfer capabilities.
 * 
 * @author Jeff McDonald
 */
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyRegistrarServlet extends AsyncSipServlet
		implements SipServletListener, SipApplicationSessionListener {

	public static SettingsManager<ProxyRegistrarSettings> settingsManager;
	public static Logger sipLogger;

	@SipApplicationKey
	public static String sessionKey(SipServletRequest req) {
		String key = getAccountName(req.getTo());

		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer("Returning sessionKey: " + key);
		}

		return key;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		settingsManager = new SettingsManager(event, ProxyRegistrarSettings.class, new ProxyRegistrarSettings());
		sipLogger = settingsManager.getSipLogger();
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer("servletCreated: " + event.getServletContext().getServletContextName());
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer("servletDestroyed: " + event.getServletContext().getServletContextName());
		}
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;
		ProxyRegistrarSettings settings = settingsManager.getCurrent();

		switch (request.getMethod()) {
		case "REGISTER":
			callflow = new RegisterCallflow(settings);
			break;
		case "INVITE":
			callflow = new ProxyCallflow(settings);
			break;
		default:
			callflow = null;
		}

		return callflow;
	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(ConsoleColors.YELLOW_BOLD + "SipApplicationSession sessionCreated... "
					+ event.getApplicationSession().getId() + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(ConsoleColors.YELLOW_BOLD + "SipApplicationSession sessionDestroyed... "
					+ event.getApplicationSession().getId() + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(ConsoleColors.YELLOW_BOLD + "SipApplicationSession sessionExpired... "
					+ event.getApplicationSession().getId() + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			sipLogger.finer(ConsoleColors.YELLOW_BOLD + "SipApplicationSession sessionReadyToInvalidate... "
					+ event.getApplicationSession().getId() + ConsoleColors.RESET);
		}
	}

}
