package org.vorpal.blade.framework.v2.proxy;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Defines a routing plan for SIP proxy operations with multiple tiers.
 * Each tier can contain multiple endpoints with parallel or serial routing modes.
 */
public class ProxyPlan implements Serializable {

	private static final long serialVersionUID = 1L;
	private String id;
	private String description;
	private Object context;
	private ArrayList<ProxyTier> tiers = new ArrayList<>();

	/** Null-safe method to get tiers, never returns null */
	private ArrayList<ProxyTier> ensureTiers() {
		if (tiers == null) {
			tiers = new ArrayList<>();
		}
		return tiers;
	}

	public ProxyPlan() {
	}

	public ProxyPlan(String id) {
		this.id = id;
	}

	public ProxyPlan(ProxyPlan that) {
		copy(that);
	}

	public ProxyPlan copy(ProxyPlan that) {
		if (that == null) {
			return this;
		}
		this.id = that.id;
		this.description = that.description;
		tiers = that.tiers != null ? new ArrayList<>(that.tiers) : new ArrayList<>();
		return this;
	}

	/**
	 * Checks if this proxy plan has no tiers defined.
	 *
	 * @return true if there are no tiers
	 */
	@JsonIgnore
	public boolean isEmpty() {
		return tiers == null || tiers.isEmpty();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public void clear() {
		if (this.tiers != null) {
			this.tiers.clear();
		}
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
