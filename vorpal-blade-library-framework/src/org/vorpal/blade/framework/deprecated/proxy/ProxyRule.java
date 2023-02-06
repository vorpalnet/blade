package org.vorpal.blade.framework.deprecated.proxy;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

public class ProxyRule implements Serializable {

	private static final long serialVersionUID = 1L;
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
