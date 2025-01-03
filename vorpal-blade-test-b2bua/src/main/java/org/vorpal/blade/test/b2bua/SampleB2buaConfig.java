package org.vorpal.blade.test.b2bua;

import java.io.Serializable;
import java.util.HashMap;

import javax.servlet.sip.Address;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.config.Configuration;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SampleB2buaConfig extends Configuration implements Serializable {
	@JsonPropertyDescription("The version of the configuration file.")
	public static final String version = "2.1";

	@JsonPropertyDescription("This is a SIP address.")
	public Address address;

//	@JsonPropertyDescription("This is an IP address.")
//	public IPv4Address ipv4Address;
//
//	@JsonPropertyDescription("This is another IP address.")
//	public IPv6Address ipv6Address;

	@JsonPropertyDescription("This is a SIP URI.")
	public URI uri;

	@JsonPropertyDescription("This is value1.")
	public String value1;

	@JsonPropertyDescription("This is value2.")
	public String value2;

	@JsonPropertyDescription("This is some type of map.")
	public HashMap<String, String> map;

}
