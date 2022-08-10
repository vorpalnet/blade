package org.vorpal.blade.framework.proxy;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class ProxyRule implements Serializable {

	private String id;
	private String description;
	private Object context;
	private ArrayList<ProxyTier> tiers = new ArrayList<>();

	public ProxyRule() {
	}

	public ProxyRule(String id) {
		this.id = id;
	}

	public ProxyRule(ProxyRule that) {
		copy(that);
	}

	public ProxyRule copy(ProxyRule that) {
		this.id = that.id;
		this.description = that.description;
		tiers = new ArrayList<>(that.tiers);
		return this;
	}

	@JsonIgnore
	public boolean isEmpty() {
		return tiers.size() == 0;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public void clear() {
		this.getTiers().clear();
	}

	public List<ProxyTier> getTiers() {
		return tiers;
	}

	public void setTiers(ArrayList<ProxyTier> tiers) {
		this.tiers = tiers;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Object getContext() {
		return context;
	}

	public void setContext(Object context) {
		this.context = context;
	}

}
