package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import inet.ipaddr.ipv6.IPv6Address;

/**
 * Jackson serializer for converting IPv6Address objects to JSON strings.
 */
public class JsonIPv6AddressSerializer extends StdSerializer<IPv6Address> {

	private static final long serialVersionUID = 1L;

	public JsonIPv6AddressSerializer() {
		this(null);
	}

	protected JsonIPv6AddressSerializer(Class<IPv6Address> t) {
		super(t);
	}

	@Override
	public void serialize(IPv6Address ipAddress, JsonGenerator generator, SerializerProvider provider)
			throws IOException {
		generator.writeString(ipAddress.toString());
	}

	@Override
	public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint)
			throws JsonMappingException {
		// Empty implementation - visitor pattern not used for this serializer
	}

}
