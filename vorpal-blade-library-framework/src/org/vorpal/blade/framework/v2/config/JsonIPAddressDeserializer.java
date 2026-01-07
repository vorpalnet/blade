package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;

/**
 * Jackson deserializer for converting JSON strings to IPAddress objects.
 */
public class JsonIPAddressDeserializer extends StdDeserializer<IPAddress> {
	private static final long serialVersionUID = 1L;

	public JsonIPAddressDeserializer() {
		this(null);
	}

	public JsonIPAddressDeserializer(Class<IPAddress> t) {
		super(t);
	}

	@Override
	public IPAddress deserialize(JsonParser jp, DeserializationContext context)
			throws IOException, JsonProcessingException {
		IPAddress ipAddress = null;

		ipAddress = new IPAddressString(jp.getValueAsString()).getAddress();

		return ipAddress;
	}

}
