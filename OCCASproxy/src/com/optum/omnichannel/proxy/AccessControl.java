package com.optum.omnichannel.proxy;

import java.io.Serializable;

import inet.ipaddr.IPAddress;
import inet.ipaddr.ipv4.IPv4Address;

public class AccessControl implements Serializable{
	public enum Permission {
		deny, allow
	};

	private String source;
	private Permission permission;
	private String proxyRuleId;

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
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
