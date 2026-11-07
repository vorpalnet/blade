package org.vorpal.blade.framework.v3.rest;

import javax.ws.rs.ext.ContextResolver;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

/// The one `ObjectMapper` a BLADE REST API writes JSON with: Jackson,
/// properties in their declared order (`@JsonPropertyOrder`), nulls left out.
///
/// Every Jackson writer Jersey has for the application (the two in
/// `blade-shared`'s `jackson-jaxrs-json-provider` are registered by annotation
/// scanning and cannot be removed) asks for its mapper through this resolver,
/// so whichever of them Jersey picks, the bytes are the same.
///
/// Not annotated `@Provider`: [RestJsonDiscoverable] registers it, once.
public class RestJson implements ContextResolver<ObjectMapper> {

	static final ObjectMapper MAPPER = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

	@Override
	public ObjectMapper getContext(Class<?> type) {
		return MAPPER;
	}
}
