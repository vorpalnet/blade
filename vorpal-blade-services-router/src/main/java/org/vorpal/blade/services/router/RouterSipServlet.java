package org.vorpal.blade.services.router;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.b2bua.B2buaServlet;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.services.router.config.RouterSettings;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class RouterSipServlet extends B2buaServlet {

//	private static final String GUCID = "Cisco-Gucid";
//	private static final String UUID = "X-Genesys-CallUUID";
//	private static final String ROUTE = "ROUTE";

//	private final static String OSM_FEATURES = "OSM-Features";
//	private final static String INITIAL_IP = "initialip";

	public static SettingsManager<RouterSettings> settingsManager;

	public static String getCallKey(SipServletRequest request) {
		String key = request.getHeader("Cisco-Gucid");
		key = (key != null) ? key : request.getHeader("X-Genesys-CallUUID");
		if (key != null) {
			sipLogger.info(request, "New call with key: " + key);
		}
		return key;
	}

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		settingsManager = new SettingsManager<RouterSettings>(event, RouterSettings.class);
		sipLogger.logConfiguration(settingsManager.getCurrent());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		try {
			settingsManager.unregister();
		} catch (Exception e) {
			sipLogger.logStackTrace(e);
		}
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		SipApplicationSession appSession = outboundRequest.getApplicationSession();

		RouterSettings config = settingsManager.getCurrent();
		config.applyRoutes(outboundRequest);
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		// do nothing;
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		// do nothing;
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		// do nothing;
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		// do nothing;
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		// do nothing;
	}

}
