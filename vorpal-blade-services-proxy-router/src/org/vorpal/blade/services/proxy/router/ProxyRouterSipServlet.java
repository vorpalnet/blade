package org.vorpal.blade.services.proxy.router;

import java.io.IOException;
import java.util.logging.Level;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.config.RouterConfig;
import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v2.proxy.ProxyPlan;
import org.vorpal.blade.framework.v2.proxy.ProxyServlet;
import org.vorpal.blade.framework.v2.proxy.ProxyTier;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyRouterSipServlet extends ProxyServlet {

	private static final long serialVersionUID = 1L;
	public static SettingsManager<RouterConfig> settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new SettingsManager<RouterConfig>(event, RouterConfig.class, new ProxyRouterConfigSample());
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager.unregister();
	}

	@Override
	public void proxyRequest(SipServletRequest request, ProxyPlan ProxyPlan) throws ServletException, IOException {

		RouterConfig config = settingsManager.getCurrent();

		URI ruri = config.findRoute(request);
		if (ruri == null) {
			ruri = request.getRequestURI();
		}

		ProxyTier proxyTier = new ProxyTier();
		proxyTier.addEndpoint(ruri);
		ProxyPlan.getTiers().add(proxyTier);

		if (sipLogger.isLoggable(Level.INFO)) {

			Address from = request.getFrom();
			Address to = request.getTo();

			sipLogger.info(request,
					"ProxyRouterSipServlet.proxyRequest - from=" + from + ", to=" + to + ", ruri=" + ruri);
		}
	}

	@Override
	public void proxyResponse(SipServletResponse response, ProxyPlan ProxyPlan) throws ServletException, IOException {
		if (sipLogger.isLoggable(Level.INFO)) {
			sipLogger.finer("ProxyRouterSipServlet.proxyResponse - status=" + response.getStatus());
		}
	}

}
