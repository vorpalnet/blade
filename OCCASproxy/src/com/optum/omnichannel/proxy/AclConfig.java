package com.optum.omnichannel.proxy;

import java.io.Serializable;
import java.util.HashMap;

import inet.ipaddr.IPAddressString;
import inet.ipaddr.format.util.AddressTrieMap;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv4.IPv4AddressAssociativeTrie;

public class AclConfig implements Serializable {
	public static AddressTrieMap<inet.ipaddr.Address, AccessControl> accessControlList;
	public static HashMap<String, ProxyRule> proxyRules;

	public AclConfig(ProxyConfig config) {

		proxyRules = new HashMap<>();
		accessControlList = new AddressTrieMap<>(new IPv4AddressAssociativeTrie());

		IPv4Address ipAddress;
		for (AccessControl a : config.getAcl()) {
			ipAddress = new IPAddressString(a.getSource()).getAddress().toIPv4();
			accessControlList.put(ipAddress, a);
		}

		for (ProxyRule p : config.getProxyRules()) {
			proxyRules.put(p.getId(), p);
		}

	}

}
