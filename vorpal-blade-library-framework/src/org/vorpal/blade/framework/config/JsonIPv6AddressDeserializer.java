package org.vorpal.blade.framework.config;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv6.IPv6Address;

public class JsonIPv6AddressDeserializer extends StdDeserializer<IPv6Address> {
	private static final long serialVersionUID = 1L;

	public JsonIPv6AddressDeserializer() {
		this(null);
	}

	public JsonIPv6AddressDeserializer(Class<IPAddress> t) {
		super(t);
	}

	@Override
	public IPv6Address deserialize(JsonParser jp, DeserializationContext context)
			throws IOException, JsonProcessingException {
		IPv6Address ipAddress = null;

		ipAddress = new IPAddressString(jp.getValueAsString()).getAddress().toIPv6();

		return ipAddress;
	}

}
