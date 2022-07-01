package org.vorpal.blade.services.loadbalancer.proxy;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

public class ProxyRule implements Serializable {

	private String id;
	private List<ProxyTier> tiers = new LinkedList<>();

	public ProxyRule() {
	}

	public ProxyRule(String id) {
		this.id = id;
	}

	public ProxyRule(ProxyRule that) {
		// shallow copy okay.
		this.id = that.id;

		// deep copy of the list required for manipulation,
		// shallow copy of the ProxyTiers okay.
		tiers = new LinkedList<>(that.tiers);
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
