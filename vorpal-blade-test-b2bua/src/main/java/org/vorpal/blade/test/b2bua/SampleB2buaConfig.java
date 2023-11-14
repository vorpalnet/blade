package org.vorpal.blade.test.b2bua;

import java.io.Serializable;
import java.util.HashMap;

import javax.servlet.sip.Address;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv6.IPv6Address;

public class SampleB2buaConfig extends Configuration implements Serializable {

	@JsonPropertyDescription("This is a SIP address.")
	public Address address;

	@JsonPropertyDescription("This is an IP address.")
	public IPv4Address ipv4Address;

	@JsonPropertyDescription("This is another IP address.")
	public IPv6Address ipv6Address;

	@JsonPropertyDescription("This is a SIP URI.")
	public URI uri;

	public String value1;
	public String value2;

	public HashMap<String, String> map;

	public SampleB2buaConfig() {

	}

}
