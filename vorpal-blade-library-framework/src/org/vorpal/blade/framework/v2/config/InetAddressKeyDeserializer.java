package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;

import inet.ipaddr.IPAddressString;

/**
 * Jackson key deserializer for converting JSON map keys to IPAddress objects.
 */
public class InetAddressKeyDeserializer extends KeyDeserializer {
	@Override
	public Object deserializeKey(final String key, final DeserializationContext ctxt)
			throws IOException, JsonProcessingException {
		return new IPAddressString(key).getAddress();
	}
}
