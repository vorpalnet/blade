package org.vorpal.blade.framework.config;

import java.io.IOException;

import javax.servlet.sip.Address;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

public class JsonAddressSerializer extends StdSerializer<Address> {

	public JsonAddressSerializer() {
		this(null);
	}
	
	protected JsonAddressSerializer(Class<Address> t) {
		super(t);
	}

	@Override
	public void serialize(Address address, JsonGenerator generator, SerializerProvider provider) throws IOException {
		generator.writeString(address.toString());

	}

}
