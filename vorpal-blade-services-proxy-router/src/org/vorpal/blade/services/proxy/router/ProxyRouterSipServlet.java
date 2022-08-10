package org.vorpal.blade.services.proxy.router;

import java.io.IOException;

import javax.annotation.Resource;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.logging.Logger.Direction;
import org.vorpal.blade.framework.proxy.ProxyRule;
import org.vorpal.blade.framework.proxy.ProxyServlet;
import org.vorpal.blade.framework.proxy.ProxyTier;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyRouterSipServlet extends ProxyServlet {

	public static SettingsManager<ProxyRouterConfig> settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		try {
			settingsManager = new SettingsManager<>(event, ProxyRouterConfig.class, new ProxyRouterConfigSample());
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
	public void proxyRequest(SipServletRequest request, ProxyRule proxyRule) throws ServletException, IOException {
		sipLogger.fine("proxyRequest... ");

		URI uri = settingsManager.getCurrent().findRoute(request);
		if (uri == null) {
			uri = request.getRequestURI();
		}

		sipLogger.fine("proxyRequest... " + uri);
		proxyRule.getTiers().add(new ProxyTier(uri));

	}

	@Override
	public void proxyResponse(SipServletResponse response, ProxyRule proxyRule) throws ServletException, IOException {
		sipLogger.fine("proxyResponse... " + response.getStatus());
	}

}
