package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Jackson deserializer for converting JSON strings to SIP Address objects.
 */
public class JsonAddressDeserializer extends StdDeserializer<Address> {
	private static final long serialVersionUID = 1L;

	public JsonAddressDeserializer() {
		this(null);
	}

	public JsonAddressDeserializer(Class<Address> t) {
		super(t);
	}

	@Override
	public Address deserialize(JsonParser jp, DeserializationContext context)
			throws IOException, JsonProcessingException {
		Address address = null;

		try {
			address = SettingsManager.sipFactory.createAddress(jp.getValueAsString());
		} catch (ServletParseException e) {
			throw new IOException(e);
		}

		return address;
	}

}
