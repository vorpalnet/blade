package org.vorpal.blade.services.proxy.registrar.v3;

import java.io.IOException;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionEvent;
import javax.servlet.sip.SipSessionListener;
import javax.servlet.sip.annotation.SipApplicationKey;

import org.vorpal.blade.framework.v2.AsyncSipServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.ConsoleColors;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class PRServlet extends AsyncSipServlet implements SipApplicationSessionListener, SipSessionListener {
//public class PRServlet extends AsyncSipServlet {
	private static final long serialVersionUID = 2804504496149776315L;
	public static SettingsManager<Settings> settingsManager;

	@SipApplicationKey
	public static String sessionKey(SipServletRequest request) {
		String key = null;

		switch (request.getMethod()) {
		case "REGISTER":
			key = getAccountName(request.getFrom());
			break;
		}

		sipLogger.finer("sessionKey method=" + request.getMethod() + ", key=" + key);
		return key;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<Settings>(event, Settings.class, new SettingsSample());
		sipLogger.finer("PRServlet.servletCreated");
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		sipLogger.finer("PRServlet.servletDestroyed...");
		settingsManager.unregister();
	}

	@Override
	protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
		Callflow callflow = null;
		switch (request.getMethod()) {
		case "REGISTER":
			callflow = new RegisterCallflow();
			break;

		case "INVITE":
			if (request.isInitial()) {
				callflow = new InviteCallflow();
			}
			break;
		}

		return callflow;
	}

	@Override
	public void sessionCreated(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession created...");
		}
	}

	@Override
	public void sessionDestroyed(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession destroyed...");

		}
	}

	@Override
	public void sessionReadyToInvalidate(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipSession sipSession = event.getSession();
			sipLogger.finer(sipSession, "sipSession readyToInvalidate...");
		}
	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, ConsoleColors.GREEN_BRIGHT + "appSession created..." + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, ConsoleColors.RED_BRIGHT + "appSession destroyed..." + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession expired...");
		}
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.FINER)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.finer(appSession, "appSession readyToInvalidate...");
		}
	}

}
