package org.vorpal.blade.framework.v3.configuration.connectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class RestConnectorEscapingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String BODY = "{\n"
			+ "  \"callerName\": \"${callerName}\",\n"
			+ "  \"greeting\": \"Hello ${callerName}!\",\n"
			+ "  \"strategy\": ${strategy},\n"
			+ "  \"sipJson\": ${sipJson}\n"
			+ "}";

	private static MemoryContext call(String callerName) {
		MemoryContext ctx = new MemoryContext();
		ctx.put("callerName", callerName);
		ctx.put("strategy", "0");
		ctx.put("sipJson", "{\"method\":\"INVITE\"}");
		return ctx;
	}

	@Test
	void quoteInAStringCannotAddAField() throws Exception {
		String evil = "Alice\", \"admin\": true, \"x\": \"";
		JsonNode json = MAPPER.readTree(RestConnector.resolveJson(BODY, call(evil)));

		assertEquals(evil, json.get("callerName").asText());
		assertEquals("Hello " + evil + "!", json.get("greeting").asText());
		assertFalse(json.has("admin"));
	}

	@Test
	void backslashesAndControlCharactersStayInsideTheString() throws Exception {
		String name = "back\\slash\nnew line\ttab";
		JsonNode json = MAPPER.readTree(RestConnector.resolveJson(BODY, call(name)));
		assertEquals(name, json.get("callerName").asText());
	}

	@Test
	void placeholdersOutsideStringsAreInsertedAsJson() throws Exception {
		JsonNode json = MAPPER.readTree(RestConnector.resolveJson(BODY, call("Alice")));
		assertEquals(0, json.get("strategy").asInt());
		assertEquals("INVITE", json.get("sipJson").get("method").asText());
	}

	@Test
	void escapedQuoteInTheTemplateKeepsStringTracking() throws Exception {
		String template = "{ \"note\": \"say \\\"${word}\\\" twice\", \"n\": ${n} }";
		MemoryContext ctx = new MemoryContext();
		ctx.put("word", "a\"b");
		ctx.put("n", "7");
		JsonNode json = MAPPER.readTree(RestConnector.resolveJson(template, ctx));
		assertEquals("say \"a\"b\" twice", json.get("note").asText());
		assertEquals(7, json.get("n").asInt());
	}

	@Test
	void jsonIsDetectedFromContentTypeOrShape() {
		Map<String, String> json = new LinkedHashMap<>();
		json.put("Content-Type", "application/json; charset=utf-8");
		assertTrue(RestConnector.isJson(json, "anything"));

		Map<String, String> form = Collections.singletonMap("content-type", "application/x-www-form-urlencoded");
		assertFalse(RestConnector.isJson(form, "{ \"looks\": \"like json\" }"));

		assertTrue(RestConnector.isJson(Collections.emptyMap(), "{ }"));
		assertFalse(RestConnector.isJson(Collections.emptyMap(), "a=b"));
	}

	@Test
	void headerValuesCannotAddHeaders() {
		assertEquals("Alice X-Evil: 1", RestConnector.stripLineBreaks("Alice\r\nX-Evil: 1"));
	}

	@Test
	void credentialHeadersAreMaskedInLogs() {
		assertTrue(RestConnector.isCredentialHeader("Authorization", null));
		assertTrue(RestConnector.isCredentialHeader("X-API-Key", null));
		assertTrue(RestConnector.isCredentialHeader("X-Amz-Security-Token", null));
		assertFalse(RestConnector.isCredentialHeader("Content-Type", null));

		org.vorpal.blade.framework.v3.configuration.auth.ApiKeyAuthentication apiKey =
				new org.vorpal.blade.framework.v3.configuration.auth.ApiKeyAuthentication();
		apiKey.setHeader("x-slc-o1-meta");
		assertTrue(RestConnector.isCredentialHeader("X-SLC-O1-Meta", apiKey));
	}
}
