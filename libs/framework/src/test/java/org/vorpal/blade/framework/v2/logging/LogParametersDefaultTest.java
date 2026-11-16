package org.vorpal.blade.framework.v2.logging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Every application's sample configuration takes its logging section from
/// [LogParametersDefault]. A property the schema marks required and the
/// defaults leave null is left out of the sample, and the Configurator then
/// refuses a file built from it ("required property not found").
class LogParametersDefaultTest {

	@Test
	void theDefaultsGiveEveryRequiredPropertyAValue() throws Exception {
		JsonNode written = new ObjectMapper().valueToTree(new LogParametersDefault());
		List<String> missing = new ArrayList<>();
		for (Field f : LogParameters.class.getDeclaredFields()) {
			JsonProperty p = f.getAnnotation(JsonProperty.class);
			if (p != null && p.required() && (!written.has(f.getName()) || written.get(f.getName()).isNull())) {
				missing.add(f.getName());
			}
		}
		assertTrue(missing.isEmpty(), "required but not in the sample: " + missing);
	}
}
