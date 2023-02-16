package org.vorpal.blade.services.proxy.balancer;

import java.io.IOException;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;

import org.vorpal.blade.framework.callflow.Callback;
import org.vorpal.blade.framework.callflow.Callflow;
import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.deprecated.proxy.ProxyRule;
import org.vorpal.blade.framework.logging.Logger.Direction;
import org.vorpal.blade.framework.proxy.ProxyPlan;
import org.vorpal.blade.framework.proxy.ProxyServlet;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class ProxyBalancerServlet extends ProxyServlet {

	public static SettingsManager<ProxyBalancerConfig> settingsManager;
	public static String servletContextName;
	
	

	@Override
	protected void servletCreated(SipServletContextEvent event) {
		try {
			
			servletContextName = "sip:" + event.getServletContext().getServletContextName();
			
			
			settingsManager = new SettingsManager<>(event, ProxyBalancerConfig.class, new ProxyBalancerConfigSample());

		} catch (ServletParseException e) {
			SettingsManager.sipLogger.logStackTrace(e);
		}
	}

	@Override
	protected void servletDestroyed(SipServletContextEvent event) {
		try {
			settingsManager.unregister();
		} catch (MBeanRegistrationException | InstanceNotFoundException e) {
			SettingsManager.sipLogger.logStackTrace(e);
		}
	}

	@Override
	public void proxyRequest(SipServletRequest request, ProxyPlan proxyPlan) throws ServletException, IOException {
		sipLogger.fine("proxyRequest... " + request.getRequestURI());

		String key = ((SipURI) request.getRequestURI()).getHost();
		ProxyPlan plan = settingsManager.getCurrent().getPlans().get(key);

		if (plan != null) {
			proxyPlan.copy(plan);
		}

	}

	@Override
	public void proxyResponse(SipServletResponse response, ProxyPlan proxyPlan) throws ServletException, IOException {
		sipLogger.fine("proxyResponse... " + response.getStatus());

	}

	@Override
	protected void doResponse(SipServletResponse response) throws ServletException, IOException {

		sipLogger.fine(response, "ProxyBalancerServlet.doResponse... status: " + response.getStatus()
				+ ", isBranchResponse: " + response.isBranchResponse());

		Callback<SipServletResponse> callback;
		try {

//			if (response.isBranchResponse() == false) {

			callback = Callflow.pullCallback(response);
			if (callback != null) {
				Callflow.getLogger().superArrow(Direction.RECEIVE, null, response, callback.getClass().getSimpleName());
				callback.accept(response);
			} else {
				Callflow.getLogger().superArrow(Direction.RECEIVE, null, response, "null");
			}

//			}

		} catch (Exception e) {
			Callflow.getLogger().logStackTrace(e);
			throw e;
		}

	}

}
