package org.vorpal.blade.services.loadbalancer.config;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.framework.proxy.ProxyRule;

import inet.ipaddr.format.util.AddressTrieMap;
import inet.ipaddr.ipv4.IPv4AddressAssociativeTrie;

public class AclConfig implements Serializable{
	public static AddressTrieMap<inet.ipaddr.Address, AccessControl> accessControlList;
	public static HashMap<String, ProxyRule> proxyRules;

	public AclConfig(ProxyConfig config) {

		proxyRules = new HashMap<>();
		accessControlList = new AddressTrieMap<inet.ipaddr.Address, AccessControl>(new IPv4AddressAssociativeTrie());

		for (AccessControl a : config.getAcl()) {
			accessControlList.put(a.getSource(), a);
		}
		
		for(ProxyRule p : config.getProxyRules()) {
			proxyRules.put(p.getId(), p);
		}

	}

}
