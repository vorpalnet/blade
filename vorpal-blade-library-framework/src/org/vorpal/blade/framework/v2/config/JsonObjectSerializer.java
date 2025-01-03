package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

public class JsonObjectSerializer extends StdSerializer<Object> {

	private static final long serialVersionUID = 1L;

	public JsonObjectSerializer() {
		this(null);
	}

	protected JsonObjectSerializer(Class<Object> t) {
		super(t);
	}

	@Override
	public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint)
			throws JsonMappingException {
		// does nothing;
	}

	@Override
	public void serialize(Object object, JsonGenerator generator, SerializerProvider provider) throws IOException {
		generator.writeString(object.toString());
	}

}
