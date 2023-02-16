package org.vorpal.blade.services.proxy.router;

import java.io.IOException;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.RouterConfig;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.proxy.ProxyPlan;
import org.vorpal.blade.framework.proxy.ProxyServlet;
import org.vorpal.blade.framework.proxy.ProxyTier;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyRouterSipServlet extends ProxyServlet {

	public static SettingsManager<RouterConfig> settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		try {
			settingsManager = new SettingsManager<>(event, RouterConfig.class, new MediaHubConfigSample());

			sipLogger.severe("BEGIN TESTS...");

//			sipLogger.logConfiguration((new ProxyRouterConfigTest()).test01());
//			sipLogger.logConfiguration((new ProxyRouterConfigTest()).test02());
			
			MediaHubConfigSample sample = new MediaHubConfigSample();
			settingsManager.getSipLogger().logConfiguration(sample);
			
			
			
//			settingsManager.getSipLogger().logConfiguration(settingsManager.getCurrent());
			

			sipLogger.severe(".....END TESTS");

		} catch (Exception e) {
			SettingsManager.sipLogger.logStackTrace(e);
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		try {
			settingsManager.unregister();
		} catch (MBeanRegistrationException | InstanceNotFoundException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void proxyRequest(SipServletRequest request, ProxyPlan ProxyPlan) throws ServletException, IOException {

		RouterConfig config = settingsManager.getCurrent();

		URI uri = config.findRoute(request);
		if (uri == null) {
			uri = request.getRequestURI();
		}

		sipLogger.fine(request, "proxyRequest... " + uri);

		ProxyTier proxyTier = new ProxyTier();
		proxyTier.addEndpoint(uri);
		ProxyPlan.getTiers().add(proxyTier);
	}

	@Override
	public void proxyResponse(SipServletResponse response, ProxyPlan ProxyPlan) throws ServletException, IOException {
		sipLogger.fine("proxyResponse... " + response.getStatus());
	}

}
