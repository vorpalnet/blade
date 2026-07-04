package org.vorpal.blade.framework.v3.configuration;

import java.io.IOException;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/// Reads a JSON string into a [Resolvable] of the given concrete type, storing
/// the raw string as the template — it does **not** parse the URI at load time
/// (the template may contain unresolved `${var}` that only resolve at runtime).
/// One generic deserializer, parameterized per concrete type by its
/// `String`-constructor reference; no `instanceof` needed.
///
/// @param <R> the concrete [Resolvable] subclass
public class ResolvableDeserializer<R extends Resolvable<?>> extends StdDeserializer<R> {
	private static final long serialVersionUID = 1L;

	private final transient Function<String, R> factory;

	public ResolvableDeserializer(Class<R> type, Function<String, R> factory) {
		super(type);
		this.factory = factory;
	}

	@Override
	public R deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		return factory.apply(parser.getValueAsString());
	}
}
