package org.vorpal.blade.services.proxy.balancer;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.framework.proxy.ProxyRule;

public class ProxyBalancerConfig implements Serializable {

	private HashMap<String, ProxyRule> rules = new HashMap<>();

	public HashMap<String, ProxyRule> getRules() {
		return rules;
	}

	public void setRules(HashMap<String, ProxyRule> rules) {
		this.rules = rules;
	}

}
