package org.vorpal.blade.framework.v2.config;

import java.io.IOException;

import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipURI;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

public class JsonSipUriDeserializer extends StdDeserializer<SipURI> {
	private static final long serialVersionUID = 1L;

	public JsonSipUriDeserializer() {
		this(null);
	}

	public JsonSipUriDeserializer(Class<SipURI> t) {
		super(t);
	}

	@Override
	public SipURI deserialize(JsonParser jp, DeserializationContext context)
			throws IOException, JsonProcessingException {
		SipURI uri = null;

		try {
			uri = (SipURI) SettingsManager.sipFactory.createURI(jp.getValueAsString());
		} catch (ServletParseException e) {
			throw new IOException(e);
		}

		return uri;
	}

}