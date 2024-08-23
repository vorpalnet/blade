package org.vorpal.blade.services.proxy.balancer;

import java.io.IOException;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.SettingsManager;
import org.vorpal.blade.framework.proxy.ProxyPlan;
import org.vorpal.blade.framework.proxy.ProxyTier;

/**
 * Extends the SettingsManager class to implement the initialize method. This is
 * used to keep track of the status (up/down) of each endpoint.
 * 
 * @author jeff
 *
 */
public class ProxyBalancerSettingsManager extends SettingsManager<ProxyBalancerConfig> {

	public ProxyBalancerSettingsManager(SipServletContextEvent event, Class<ProxyBalancerConfig> clazz)
			throws ServletException, IOException {
		super(event, clazz);
	}

	/**
	 * Creates a map of endpoints and their statuses.
	 * 
	 * @param config
	 */
	@Override
	public void initialize(ProxyBalancerConfig config) throws ServletParseException {

		for (Entry<String, ProxyPlan> planEntry : config.getPlans().entrySet()) {
			for (ProxyTier tier : planEntry.getValue().getTiers()) {
				for (URI uri : tier.getEndpoints()) {
					config.endpointStatus.put(((SipURI) uri).getHost(), ProxyBalancerConfig.EndpointStatus.up);
				}
			}

		}

	}

}
