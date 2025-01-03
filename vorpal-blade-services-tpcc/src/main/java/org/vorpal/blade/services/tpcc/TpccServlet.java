package org.vorpal.blade.services.tpcc;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSessionEvent;
import javax.servlet.sip.SipSessionListener;
import javax.ws.rs.container.AsyncResponse;

import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.ConsoleColors;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
//public class TransferServlet extends B2buaServlet implements TransferListener {
public class TpccServlet extends B2buaServlet //
		implements SipApplicationSessionListener, SipSessionListener {
	private static final long serialVersionUID = 1L;
	public static SettingsManager<TpccSettings> settingsManager;

	public static Map<String, AsyncResponse> responseMap = new ConcurrentHashMap<>();

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<>(event, TpccSettings.class, new TpccSettingsSample());
		sipLogger.info("TpccServlet servletCreated...");
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		try {
			sipLogger.info("TpccServlet servletDestroyed...");
			settingsManager.unregister();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {

	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		// TODO Auto-generated method stub

	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.INFO)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.info(appSession, ConsoleColors.GREEN_BRIGHT + "appSession created..." + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.INFO)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.info(appSession, ConsoleColors.RED_BRIGHT + "appSession destroyed..." + ConsoleColors.RESET);
		}
	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.INFO)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.info(appSession, "appSession sessionExpired...");
		}
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		if (sipLogger.isLoggable(Level.INFO)) {
			SipApplicationSession appSession = event.getApplicationSession();
			sipLogger.info(appSession, "appSession sessionReadyToInvalidate...");
		}
	}

	@Override
	public void sessionCreated(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.INFO)) {
			SipSession sipSession = event.getSession();
			sipLogger.info(sipSession, "sipSession sessionCreated...");
		}
	}

	@Override
	public void sessionDestroyed(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.INFO)) {
			SipSession sipSession = event.getSession();
			sipLogger.info(sipSession, "sipSession sessionDestroyed...");

		}
	}

	@Override
	public void sessionReadyToInvalidate(SipSessionEvent event) {
		if (sipLogger.isLoggable(Level.INFO)) {
			SipSession sipSession = event.getSession();
			sipLogger.info(sipSession, "sipSession sessionReadyToInvalidate...");
		}
	}

}
