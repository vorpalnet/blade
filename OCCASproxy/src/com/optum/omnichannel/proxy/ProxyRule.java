package com.optum.omnichannel.proxy;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import org.vorpal.blade.framework.proxy.ProxyTier;

public class ProxyRule implements Serializable {

	private String id;
	private List<ProxyTier> tiers = new LinkedList<>();

	public ProxyRule() {
	}

	public ProxyRule(ProxyRule that) {
		id = new String(that.id);
		tiers = new LinkedList<>(that.getTiers());
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public List<ProxyTier> getTiers() {
		return tiers;
	}

	public void setTiers(List<ProxyTier> tiers) {
		this.tiers = tiers;
	}

}
