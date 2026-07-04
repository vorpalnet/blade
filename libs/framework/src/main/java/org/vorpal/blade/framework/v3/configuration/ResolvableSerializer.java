package org.vorpal.blade.framework.v3.configuration;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/// Writes any [Resolvable] back to JSON as its plain template string, so config
/// files round-trip as strings (matching how `SipURI`/`Address` are persisted).
/// Registered once on the base type; covers every subclass.
public class ResolvableSerializer extends StdSerializer<Resolvable<?>> {
	private static final long serialVersionUID = 1L;

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public ResolvableSerializer() {
		super((Class) Resolvable.class);
	}

	@Override
	public void serialize(Resolvable<?> value, JsonGenerator gen, SerializerProvider provider) throws IOException {
		gen.writeString(value == null ? null : value.getTemplate());
	}
}
