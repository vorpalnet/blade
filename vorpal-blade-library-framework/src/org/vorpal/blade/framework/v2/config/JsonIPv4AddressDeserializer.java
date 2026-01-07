package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4Address;

/**
 * Jackson deserializer for converting JSON strings to IPv4Address objects.
 */
public class JsonIPv4AddressDeserializer extends StdDeserializer<IPv4Address> {
	private static final long serialVersionUID = 1L;

	public JsonIPv4AddressDeserializer() {
		this(null);
	}

	public JsonIPv4AddressDeserializer(Class<IPAddress> t) {
		super(t);
	}

	@Override
	public IPv4Address deserialize(JsonParser jp, DeserializationContext context)
			throws IOException, JsonProcessingException {
		IPv4Address ipAddress = null;

		ipAddress = new IPAddressString(jp.getValueAsString()).getAddress().toIPv4();

		return ipAddress;
	}

}
