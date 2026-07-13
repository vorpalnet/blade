package org.vorpal.blade.services.proxy.balancer;

import java.io.IOException;
import java.util.Map.Entry;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.services.proxy.balancer.config.BalancerConfig;
import org.vorpal.blade.services.proxy.balancer.config.Endpoint;
import org.vorpal.blade.services.proxy.balancer.config.Tier;

/**
 * Extends the SettingsManager class to implement the initialize method:
 * seeds the endpoint health map (keyed by endpoint NAME) from the registry,
 * and validates that every tier's endpoint and site references resolve.
 * Health objects are carried across config publishes through the node-level
 * registry below — an endpoint whose name survives a publish keeps its
 * status, history, and traffic counters; a brand-new name starts 'up'.
 *
 * @author jeff
 *
 */
public class ProxyBalancerSettingsManager extends SettingsManager<BalancerConfig> {

	private static final Logger logger = Logger.getLogger(ProxyBalancerSettingsManager.class.getName());

	/// Node-level health registry, keyed by endpoint NAME (the registry's
	/// stable identity). Static per WAR classloader like the strategy rotation
	/// counters: it outlives individual config objects so a publish doesn't
	/// wipe history — names still configured keep their EndpointHealth object;
	/// names removed from the registry are dropped.
	private static final ConcurrentHashMap<String, EndpointHealth> nodeHealth = new ConcurrentHashMap<>();

	public ProxyBalancerSettingsManager(SipServletContextEvent event, Class<BalancerConfig> clazz,
			BalancerConfig sample) throws ServletException, IOException {
		super(event, clazz, sample);
	}

	@Override
	public void initialize(BalancerConfig config) throws ServletParseException {

		for (String name : config.getEndpoints().keySet()) {
			config.endpointHealth.put(name, nodeHealth.computeIfAbsent(name, (k) -> new EndpointHealth()));
		}
		nodeHealth.keySet().retainAll(config.getEndpoints().keySet());

		// a dangling reference routes nothing — say so at publish time, loudly
		for (Entry<String, ArrayList<Tier>> planEntry : config.getPlans().entrySet()) {
			for (Tier tier : planEntry.getValue()) {
				for (String name : tier.getEndpoints()) {
					if (!config.getEndpoints().containsKey(name)) {
						logger.warning("proxy-balancer config: plan '" + planEntry.getKey() + "' tier '"
								+ tier.getName() + "' references unknown endpoint '" + name + "'");
					}
				}
			}
		}

		// same treatment for endpoint->site references (map view grouping)
		for (Entry<String, Endpoint> endpointEntry : config.getEndpoints().entrySet()) {
			String site = endpointEntry.getValue().getSite();
			if (site != null && !config.getSites().containsKey(site)) {
				logger.warning("proxy-balancer config: endpoint '" + endpointEntry.getKey()
						+ "' references unknown site '" + site + "'");
			}
		}
		if (config.getClusterSite() != null && !config.getSites().containsKey(config.getClusterSite())) {
			logger.warning("proxy-balancer config: clusterSite '" + config.getClusterSite()
					+ "' is not in the sites registry");
		}

		// apply a changed pingInterval/pingEnabled NOW, not after the old
		// interval runs out (no-op on the initial load, before the cycle starts)
		OptionsPingCallflow.reschedule();

	}

}
