package org.vorpal.blade.services.context;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v3.B2buaServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Captures raw inbound SIP headers on initial requests so external apps can
/// retrieve them via the REST API after a cloud-provider trunk has scrubbed
/// or rewritten them.
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ContextServlet extends B2buaServlet implements B2buaListener {
	private static final long serialVersionUID = 1L;

	public static final String RAW_HEADERS_ATTR = "RAW_HEADERS";

	public static SettingsManager<ContextSettings> settingsManager;

	public static SettingsManager<ContextSettings> getSettingsManager() {
		return settingsManager;
	}

	public static void setSettingsManager(SettingsManager<ContextSettings> settingsManager) {
		ContextServlet.settingsManager = settingsManager;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		try {
			settingsManager = new SettingsManager<>(event, ContextSettings.class, new ContextSettingsSample());
			sipLogger.fine("ContextServlet.servletCreated");
		} catch (Exception e) {
			sipLogger.severe(e);
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		try {
			if (settingsManager != null) {
				settingsManager.unregister();
			}
			sipLogger.fine("ContextServlet.servletDestroyed");
		} catch (Exception e) {
			sipLogger.severe(e);
		}
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		SipApplicationSession sas = outboundRequest.getApplicationSession();
		if (sas.getAttribute(RAW_HEADERS_ATTR) == null) {
			Map<String, String> headers = new LinkedHashMap<>();
			for (String name : iterable(outboundRequest.getHeaderNames())) {
				headers.put(name, outboundRequest.getHeader(name));
			}
			sas.setAttribute(RAW_HEADERS_ATTR, headers);
		}
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
	}

	private static <T> Iterable<T> iterable(final java.util.Iterator<T> it) {
		return () -> it;
	}

}
