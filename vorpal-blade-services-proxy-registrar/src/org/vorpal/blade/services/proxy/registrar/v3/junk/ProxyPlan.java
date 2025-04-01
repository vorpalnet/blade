package org.vorpal.blade.services.proxy.registrar.v3.junk;

import java.io.Serializable;
import java.util.ArrayList;

import javax.servlet.sip.SipServletRequest;

public class ProxyPlan implements Serializable {
	private static final long serialVersionUID = 1L;
	public SipServletRequest request;
	public String id;
	public String description;
	public Object context;
	public ArrayList<ProxyTier> tiers = new ArrayList<>();

	public ProxyPlan() {
	}

	public ProxyPlan(SipServletRequest request) {
		this.request = request;
	}

	public ProxyTier createProxyTier() {
		ProxyTier tier = new ProxyTier();
		tiers.add(tier);
		return tier;
	}

	public ProxyTier createProxyTier(boolean parallel, int seconds) {
		ProxyTier tier = new ProxyTier(parallel, seconds);
		return tier;
	}

	public ProxyTier addProxyTier(ProxyTier proxyTier) {
		tiers.add(proxyTier);
		return proxyTier;
	}

}
