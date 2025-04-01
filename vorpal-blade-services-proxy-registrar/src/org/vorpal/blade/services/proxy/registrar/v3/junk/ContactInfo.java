package org.vorpal.blade.services.proxy.registrar.v3.junk;

import java.io.Serializable;

import javax.servlet.sip.Address;

public class ContactInfo implements Serializable {
	private Address address;
	private long expiration;

	public ContactInfo(Address address, long expiration) {
		this.address = address;
		this.expiration = expiration;
	}

	public Address getAddress() {
		return address;
	}

	public void setAddress(Address address) {
		this.address = address;
	}

	public long getExpiration() {
		return expiration;
	}

	public void setExpiration(long expiration) {
		this.expiration = expiration;
	}
	
}
