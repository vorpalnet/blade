package org.vorpal.blade.services.proxy.router;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
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
		sipLogger.finer(request, "ProxyRouterSipServlet - proxyRequest, begin...");

		RouterConfig config = settingsManager.getCurrent();
		sipLogger.finer(request, "ProxyRouterSipServlet - proxyRequest, 1");

		URI uri = config.findRoute(request);
		sipLogger.finer(request, "ProxyRouterSipServlet - proxyRequest, 2");
		if (uri == null) {
			sipLogger.finer(request, "ProxyRouterSipServlet - proxyRequest, 3");
			uri = request.getRequestURI();
		}
		sipLogger.finer(request, "ProxyRouterSipServlet - proxyRequest, 4");

		sipLogger.finer(request, "ProxyRouterSipServlet - proxyRequest... requestUri=" + uri);

		ProxyTier proxyTier = new ProxyTier();
		sipLogger.finer(request, "ProxyRouterSipServlet - proxyRequest, 5");
		proxyTier.addEndpoint(uri);
		sipLogger.finer(request, "ProxyRouterSipServlet - proxyRequest, 6");
		ProxyPlan.getTiers().add(proxyTier);

		sipLogger.finer(request, "ProxyRouterSipServlet - proxyRequest, end.");
	}

	@Override
	public void proxyResponse(SipServletResponse response, ProxyPlan ProxyPlan) throws ServletException, IOException {
		sipLogger.finer("ProxyRouterSipServlet - proxyResponse... " + response.getStatus());
	}

}
