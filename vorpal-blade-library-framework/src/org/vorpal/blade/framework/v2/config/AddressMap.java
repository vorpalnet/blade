package org.vorpal.blade.framework.v2.config;

import inet.ipaddr.Address;
import inet.ipaddr.format.util.AddressTrieMap;
import inet.ipaddr.ipv4.IPv4AddressAssociativeTrie;

public class AddressMap extends AddressTrieMap<Address, Translation> {
	private static final long serialVersionUID = 1L;

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public AddressMap() {
		super(new IPv4AddressAssociativeTrie());
	}
}
