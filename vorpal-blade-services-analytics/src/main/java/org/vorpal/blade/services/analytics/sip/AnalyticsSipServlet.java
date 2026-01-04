package org.vorpal.blade.services.analytics.sip;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.b2bua.B2buaListener;
import org.vorpal.blade.framework.v2.b2bua.B2buaServlet;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.logging.Color;
import org.vorpal.blade.services.analytics.jms.JmsPublisher;

@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class AnalyticsSipServlet extends B2buaServlet implements B2buaListener, SipApplicationSessionListener {
	private static final long serialVersionUID = 1L;

	public static SettingsManager<AnalyticsConfig> settingsManager;
	public static JmsPublisher jmsPublisher;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {

		System.out.println(Color.RED("AnalyticsSipServlet.servletCreated - B2buaServlet"));

		try {
			settingsManager = new SettingsManager<AnalyticsConfig>(event, AnalyticsConfig.class,
					new AnalyticsConfigSample());

			jmsPublisher = new JmsPublisher();
			jmsPublisher.init();

		} catch (Exception e) {
			sipLogger.severe(e);
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		System.out.println(Color.RED("AnalyticsSipServlet.servletDestroyed - B2buaServlet"));

		if (jmsPublisher != null) {
			jmsPublisher.close();
		}
	}

	@Override
	public void callStarted(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.fine(outboundRequest, "AnalyticsSipServlet.callStarted");

	}

	@Override
	public void callAnswered(SipServletResponse outboundResponse) throws ServletException, IOException {
		sipLogger.fine(outboundResponse, "AnalyticsSipServlet.callAnswered");
	}

	@Override
	public void callConnected(SipServletRequest outboundRequest) throws ServletException, IOException {

		sipLogger.fine(outboundRequest, "AnalyticsSipServlet.callConnected");

	}

	@Override
	public void callCompleted(SipServletRequest outboundRequest) throws ServletException, IOException {
		sipLogger.fine(outboundRequest, "AnalyticsSipServlet.callCompleted");

	}

	@Override
	public void callDeclined(SipServletResponse outboundResponse) throws ServletException, IOException {

		sipLogger.fine(outboundResponse, "AnalyticsSipServlet.callDeclined");

	}

	@Override
	public void callAbandoned(SipServletRequest outboundRequest) throws ServletException, IOException {

		sipLogger.fine(outboundRequest, "AnalyticsSipServlet.callAbandoned");

	}

	@Override
	public void sessionCreated(SipApplicationSessionEvent event) {
		sipLogger.fine(event.getApplicationSession(), "AnalyticsSipServlet.sessionCreated");

	}

	@Override
	public void sessionDestroyed(SipApplicationSessionEvent event) {
		sipLogger.fine(event.getApplicationSession(), "AnalyticsSipServlet.sessionDestroyed");

	}

	@Override
	public void sessionExpired(SipApplicationSessionEvent event) {
		sipLogger.fine(event.getApplicationSession(), "AnalyticsSipServlet.sessionExpired");

	}

	@Override
	public void sessionReadyToInvalidate(SipApplicationSessionEvent event) {
		sipLogger.fine(event.getApplicationSession(), "AnalyticsSipServlet.sessionReadyToInvalidate");

	}

}
