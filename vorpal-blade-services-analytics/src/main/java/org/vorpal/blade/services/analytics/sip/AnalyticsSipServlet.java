package org.vorpal.blade.services.analytics.sip;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.analytics.Application;
import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.SettingsManager;

@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class AnalyticsSipServlet extends B2buaServlet implements B2buaListener, SipApplicationSessionListener {
	private static final long serialVersionUID = 1L;

	public static SettingsManager<AnalyticsConfig> settingsManager;
//	public static JmsPublisher jmsPublisher;

	public static Application application;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {

		try {
			settingsManager = new SettingsManager<AnalyticsConfig>(event, AnalyticsConfig.class,
					new AnalyticsConfigSample());

			sipLogger.fine("AnalyticsSipServlet.servletCreated");
		} catch (Exception e) {
			sipLogger.severe(e);
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		sipLogger.fine("AnalyticsSipServlet.servletDestroyed");
	}

	public static long combineToLong(long timestamp, int otherValue) {
		sipLogger.finer("AnalyticsSipServlet.combineToLong");
		// Convert timestamp to 32 bits (takes the lower 32 bits)
		int timestamp32 = (int) timestamp;

		// Combine: shift timestamp to upper 32 bits, OR with other value in lower 32
		// bits
		// Use 0xFFFFFFFFL mask to prevent sign extension of otherValue
		return ((long) timestamp32 << 32) | (otherValue & 0xFFFFFFFFL);
	}

	// Helper method to extract the timestamp back
	public static int extractTimestamp(long combined) {
		sipLogger.finer("AnalyticsSipServlet.extractTimestamp");
		return (int) (combined >>> 32);
	}

	// Helper method to extract the other value back
	public static int extractOtherValue(long combined) {
		sipLogger.finer("AnalyticsSipServlet.extractOtherValue");
		return (int) combined;
	}

	public static long getSessionId(SipApplicationSession appSession) {
		sipLogger.finer("AnalyticsSipServlet.getSessionId");
		String vTimestamp = Callflow.getVorpalTimestamp(appSession);
		Long timestamp = Long.parseLong(vTimestamp, 16);
		Integer otherValue = Integer.parseUnsignedInt(Callflow.getVorpalSessionId(appSession), 16);

		long sessionId = combineToLong(timestamp, otherValue);
		return sessionId;
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.info(outboundRequest, "AnalyticsSipServlet.callStarted");
	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		sipLogger.info(outboundResponse, "AnalyticsSipServlet.callAnswered");
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.info(outboundRequest, "AnalyticsSipServlet.callConnected");
	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.info(outboundRequest, "AnalyticsSipServlet.callCompleted");
	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {
		sipLogger.info(outboundResponse, "AnalyticsSipServlet.callDeclined");
	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.info(outboundRequest, "AnalyticsSipServlet.callAbandoned");
	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {
		sipLogger.info(event.getApplicationSession(), "AnalyticsSipServlet.sessionCreated");
	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		sipLogger.info(event.getApplicationSession(), "AnalyticsSipServlet.sessionDestroyed");
	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		sipLogger.info(event.getApplicationSession(), "AnalyticsSipServlet.sessionExpired");
	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		sipLogger.info(event.getApplicationSession(), "AnalyticsSipServlet.sessionReadyToInvalidate");
	}

}
