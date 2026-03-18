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

	/**
	 * Default constructor for JSON deserialization.
	 */
	public ProxyPlan() {
	}

	/**
	 * Constructs a ProxyPlan with the specified ID.
	 *
	 * @param id the unique identifier for this plan
	 */
	public ProxyPlan(String id) {
		this.id = id;
	}

	/**
	 * Copy constructor that creates a new ProxyPlan from an existing one.
	 *
	 * @param that the ProxyPlan to copy from
	 */
	public ProxyPlan(ProxyPlan that) {
		copy(that);
	}

	/**
	 * Copies the properties from another ProxyPlan into this one.
	 *
	 * @param that the ProxyPlan to copy from
	 * @return this ProxyPlan for method chaining
	 */
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

	/**
	 * Returns the unique identifier for this proxy plan.
	 *
	 * @return the plan ID
	 */
	public String getId() {
		return id;
	}

	/**
	 * Sets the unique identifier for this proxy plan.
	 *
	 * @param id the plan ID
	 */
	public void setId(String id) {
		this.id = id;
	}

	/**
	 * Removes all tiers from this proxy plan.
	 */
	public void clear() {
		if (this.tiers != null) {
			this.tiers.clear();
		}
	}

	/**
	 * Returns the list of proxy tiers in this plan.
	 *
	 * @return the list of tiers
	 */
	public List<ProxyTier> getTiers() {
		return tiers;
	}

	/**
	 * Sets the list of proxy tiers for this plan.
	 *
	 * @param tiers the list of tiers
	 */
	public void setTiers(ArrayList<ProxyTier> tiers) {
		this.tiers = tiers;
	}

	/**
	 * Returns the human-readable description of this proxy plan.
	 *
	 * @return the description
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * Sets the human-readable description of this proxy plan.
	 *
	 * @param description the description
	 */
	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * Returns the application context object associated with this plan.
	 *
	 * @return the context object, or null if not set
	 */
	public Object getContext() {
		return context;
	}

	/**
	 * Sets the application context object for this plan.
	 * This can be used to store application-specific data.
	 *
	 * @param context the context object
	 */
	public void setContext(Object context) {
		this.context = context;
	}

}
