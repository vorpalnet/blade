package org.vorpal.blade.services.loadbalancer.config;

import java.util.HashMap;

import org.vorpal.blade.services.loadbalancer.proxy.ProxyRule;


/**
 * @author jeff
 *
 */
public class LoadBalancerConfig extends HashMap<String, ProxyRule> {
	private boolean stateless = true;

	public boolean isStateless() {
		return stateless;
	}

	public void setStateless(boolean stateless) {
		this.stateless = stateless;
	}

}
