package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import javax.servlet.sip.SipURI;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Jackson serializer for converting SipURI objects to JSON strings.
 */
public class JsonSipUriSerializer extends StdSerializer<SipURI> {

	private static final long serialVersionUID = 1L;

	public JsonSipUriSerializer() {
		this(null);
	}

	protected JsonSipUriSerializer(Class<SipURI> t) {
		super(t);
	}

	@Override
	public void serialize(SipURI uri, JsonGenerator generator, SerializerProvider provider) throws IOException {
		generator.writeString(uri.toString());

	}

	@Override
	public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint)
			throws JsonMappingException {
		// Empty implementation - visitor pattern not used for this serializer
	}

}
