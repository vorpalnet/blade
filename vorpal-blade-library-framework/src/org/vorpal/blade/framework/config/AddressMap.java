package org.vorpal.blade.framework.config;

import inet.ipaddr.Address;
import inet.ipaddr.format.util.AddressTrieMap;
import inet.ipaddr.ipv4.IPv4AddressAssociativeTrie;

public class AddressMap extends AddressTrieMap<Address, Translation> {
	public AddressMap() {
		super(new IPv4AddressAssociativeTrie());
	}
}
