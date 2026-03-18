package org.vorpal.blade.framework.v2.transfer.api;

import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

/**
 * This class modifies the default objectmapper to not serialize null items.
 */
@Provider
@Produces(MediaType.APPLICATION_JSON)
public class JsonProvider extends JacksonJaxbJsonProvider {
	private static final ObjectMapper objectMapper = new ObjectMapper();
	static {
		// allow only non-null fields to be serialized
		objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

	}

	public JsonProvider() {
		super.setMapper(objectMapper);
	}
}