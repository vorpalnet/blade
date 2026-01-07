package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import inet.ipaddr.IPAddress;

/**
 * Jackson serializer for converting IPv4Address objects to JSON strings.
 */
public class JsonIPv4AddressSerializer extends StdSerializer<IPAddress> {

	private static final long serialVersionUID = 1L;

	public JsonIPv4AddressSerializer() {
		this(null);
	}

	protected JsonIPv4AddressSerializer(Class<IPAddress> t) {
		super(t);
	}

	@Override
	public void serialize(IPAddress ipAddress, JsonGenerator generator, SerializerProvider provider)
			throws IOException {
		generator.writeString(ipAddress.toString());
	}

	@Override
	public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint)
			throws JsonMappingException {
		// Empty implementation - visitor pattern not used for this serializer
	}

}
