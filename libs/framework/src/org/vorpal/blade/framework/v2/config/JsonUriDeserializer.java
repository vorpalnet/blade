package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.URI;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Jackson deserializer for converting JSON strings to SIP URI objects.
 */
public class JsonUriDeserializer extends StdDeserializer<URI> {
	private static final long serialVersionUID = 1L;
	

	public JsonUriDeserializer() {
		this(null);
	}

	public JsonUriDeserializer(Class<URI> t) {
		super(t);
	}

	@Override
	public URI deserialize(JsonParser jp, DeserializationContext context) throws IOException, JsonProcessingException {
		URI uri = null;

		try {
			uri = SettingsManager.sipFactory.createURI(jp.getValueAsString());
		} catch (ServletParseException e) {
			throw new IOException(e);
		}

		return uri;
	}

}
