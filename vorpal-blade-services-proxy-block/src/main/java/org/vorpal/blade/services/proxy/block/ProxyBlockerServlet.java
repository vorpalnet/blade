package org.vorpal.blade.services.proxy.block;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.proxy.ProxyPlan;
import org.vorpal.blade.framework.v2.proxy.ProxyServlet;
import org.vorpal.blade.framework.v2.proxy.ProxyTier;
import org.vorpal.blade.services.proxy.block.optimized.OptimizedBlockConfig;
import org.vorpal.blade.services.proxy.block.simple.SimpleBlockConfigSample;

@WebListener
@javax.servlet.sip.annotation.SipApplication(name = "block", distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyBlockerServlet extends ProxyServlet {

	private static final long serialVersionUID = 1L;
	public static BlockSettingsManager settingsManager;

	@Override
	protected void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
		settingsManager = new BlockSettingsManager(event, new SimpleBlockConfigSample());
		sipLogger.info("servletCreated...");
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
		sipLogger.info("servletDestroyed...");
		settingsManager.unregister();
	}

	@Override
	public void proxyRequest(SipServletRequest request, ProxyPlan ProxyPlan) throws ServletException, IOException {

		OptimizedBlockConfig config = settingsManager.getOptimizedConfig();

		String strUri = OptimizedBlockConfig.forwardTo(config, request);
		URI uri;
		uri = sipFactory.createURI(strUri);
		Callflow.copyParameters(request.getRequestURI(), uri);
		sipLogger.fine(request, "proxying request to: " + uri);

		ProxyTier proxyTier = new ProxyTier();
		proxyTier.addEndpoint(uri);
		ProxyPlan.getTiers().add(proxyTier);
	}

	@Override
	public void proxyResponse(SipServletResponse response, ProxyPlan ProxyPlan) throws ServletException, IOException {
		sipLogger.finer("proxyResponse... " + response.getStatus());
	}

}
