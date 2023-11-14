package org.vorpal.blade.framework.config;

import java.io.IOException;

import javax.servlet.sip.URI;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.MapType;

public class JsonUriSerializer extends StdSerializer<URI> {

	private static final long serialVersionUID = 1L;

	public JsonUriSerializer() {
		this(null);
	}

	protected JsonUriSerializer(Class<URI> t) {
		super(t);
	}

	@Override
	public void serialize(URI uri, JsonGenerator generator, SerializerProvider provider) throws IOException {
		generator.writeString(uri.toString());

	}

	@Override
	public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint)
			throws JsonMappingException {

		System.out.println("calling... " + this.getClass().getName() + ".acceptJsonFormatVisitor()");

	}

}
