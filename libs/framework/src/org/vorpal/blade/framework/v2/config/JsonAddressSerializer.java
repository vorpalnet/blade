package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import javax.servlet.sip.Address;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Jackson serializer for converting SIP Address objects to JSON strings.
 */
public class JsonAddressSerializer extends StdSerializer<Address> {

	private static final long serialVersionUID = 1L;

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

	@Override
	public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint)
			throws JsonMappingException {
		// Empty implementation - visitor pattern not used for this serializer
	}

}
