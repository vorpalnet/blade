package org.vorpal.blade.services.loadbalancer.config;

import java.io.Serializable;

import inet.ipaddr.IPAddress;

public class AccessControl implements Serializable{
	public enum Permission {
		deny, allow
	};

	private IPAddress source;
	private Permission permission;
	private String proxyRuleId;

	public IPAddress getSource() {
		return source;
	}

	public void setSource(IPAddress source) {
		this.source = source;
	}

	public Permission getPermission() {
		return permission;
	}

	public void setPermission(Permission permission) {
		this.permission = permission;
	}

	public String getProxyRuleId() {
		return proxyRuleId;
	}

	public void setProxyRuleId(String proxyRuleId) {
		this.proxyRuleId = proxyRuleId;
	}



}
