package org.vorpal.blade.services.proxy.balancer;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.proxy.ProxyPlan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle;

@JsonSchemaTitle("Proxy Balancer")
public class ProxyBalancerConfig extends Configuration implements Serializable {
	
	private static final long serialVersionUID = 1L;

	public enum EndpointStatus{
		up, down
	}
	
	public enum TierStatus{
		available, degraded, unavailable
	}
	
	public enum PlanStatus{
		optimized, impaired, faulty
	}	
	
	@JsonIgnore
	public HashMap<String, EndpointStatus> endpointStatus = new HashMap<>();

	@JsonIgnore
	public HashMap<String, TierStatus> tierStatus = new HashMap<>();

	@JsonIgnore
	public HashMap<String, PlanStatus> planStatus = new HashMap<>();

	@JsonPropertyDescription("Interval in seconds between health check pings to endpoints.")
	@JsonProperty(defaultValue = "60")
	public Integer pingInterval = 60;

	@JsonPropertyDescription("Map of named proxy plans defining load-balancing tiers and endpoints.")
	private HashMap<String,	ProxyPlan> plans = new HashMap<>();

	public HashMap<String, ProxyPlan> getPlans() {
		return plans;
	}

	public void setPlans(HashMap<String, ProxyPlan> plans) {
		this.plans = plans;
	}
	
	public ProxyPlan addPlan(String key, ProxyPlan value) {
		return this.plans.put(key, value);
	}
	
	public ProxyPlan removePlan(String key) {
		return this.plans.remove(key);
	}

	public Integer getPingInterval() {
		return pingInterval;
	}

	public void setPingInterval(Integer pingInterval) {
		this.pingInterval = pingInterval;
	}
	
	
	

}
