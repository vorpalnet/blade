package org.vorpal.blade.framework.config;

import java.io.IOException;

import javax.servlet.sip.Address;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import inet.ipaddr.IPAddress;

public class JsonIPAddressSerializer extends StdSerializer<IPAddress> {

	public JsonIPAddressSerializer() {
		this(null);
	}
	
	protected JsonIPAddressSerializer(Class<IPAddress> t) {
		super(t);
	}

	@Override
	public void serialize(IPAddress ipAddress, JsonGenerator generator, SerializerProvider provider) throws IOException {
		generator.writeString(ipAddress.toString());
	}

}
