package org.vorpal.blade.services.loadbalancer.config;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.services.loadbalancer.proxy.ProxyRule;


public class ProxyConfig implements Serializable {
	
	private HashMap<String, ProxyRule> rules = new HashMap<>();
	

	private boolean stateless = true;

	public boolean isStateless() {
		return stateless;
	}

	public void setStateless(boolean stateless) {
		this.stateless = stateless;
	}

	public HashMap<String, ProxyRule> getRules() {
		return rules;
	}

	public void setRules(HashMap<String, ProxyRule> rules) {
		this.rules = rules;
	}
	
	

}
