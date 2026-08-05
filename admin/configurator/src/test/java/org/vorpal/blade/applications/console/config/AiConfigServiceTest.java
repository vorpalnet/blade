package org.vorpal.blade.applications.console.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Unit tests for AiConfigService's pure helpers — no container, no network.
public class AiConfigServiceTest {

	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String SCHEMA = "{" //
			+ "\"type\":\"object\",\"properties\":{" //
			+ "  \"name\":{\"type\":\"string\"}," //
			+ "  \"password\":{\"type\":\"string\",\"format\":\"password\"}," //
			+ "  \"gateway\":{\"$ref\":\"#/$defs/Gateway\"}," //
			+ "  \"trunks\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/$defs/Gateway\"}}" //
			+ "}," //
			+ "\"$defs\":{\"Gateway\":{\"type\":\"object\",\"properties\":{" //
			+ "  \"host\":{\"type\":\"string\"}," //
			+ "  \"secret\":{\"type\":\"string\",\"format\":\"password\"}" //
			+ "}}}}";

	@Test
	public void redactsPasswordFormatFieldsIncludingRefsAndArrays() throws Exception {
		JsonNode schema = mapper.readTree(SCHEMA);
		JsonNode config = mapper.readTree("{" //
				+ "\"name\":\"alice\"," //
				+ "\"password\":\"hunter2\"," //
				+ "\"gateway\":{\"host\":\"gw1\",\"secret\":\"s3cret\"}," //
				+ "\"trunks\":[{\"host\":\"gw2\",\"secret\":\"t0psecret\"}]}");

		JsonNode redacted = AiConfigService.redactSecrets(config, schema);

		assertEquals("alice", redacted.get("name").asText());
		assertEquals(AiConfigService.REDACTED, redacted.get("password").asText());
		assertEquals("gw1", redacted.get("gateway").get("host").asText());
		assertEquals(AiConfigService.REDACTED, redacted.get("gateway").get("secret").asText());
		assertEquals(AiConfigService.REDACTED, redacted.get("trunks").get(0).get("secret").asText());
	}

	@Test
	public void redactsCredentialPrefixedValuesRegardlessOfSchema() throws Exception {
		JsonNode schema = mapper.readTree("{\"type\":\"object\"}");
		JsonNode config = mapper.readTree("{\"anything\":\"{AES}AbCd==\",\"other\":\"{CLEARTEXT}pw\",\"plain\":\"ok\"}");

		JsonNode redacted = AiConfigService.redactSecrets(config, schema);

		assertEquals(AiConfigService.REDACTED, redacted.get("anything").asText());
		assertEquals(AiConfigService.REDACTED, redacted.get("other").asText());
		assertEquals("ok", redacted.get("plain").asText());
	}

	@Test
	public void restoreRedactedRoundTripsSecretsFromBaseline() throws Exception {
		JsonNode original = mapper.readTree("{\"password\":\"{AES}AbCd==\",\"gateway\":{\"secret\":\"s3cret\"}}");
		JsonNode proposed = mapper.readTree("{" //
				+ "\"password\":\"" + AiConfigService.REDACTED + "\"," //
				+ "\"gateway\":{\"secret\":\"" + AiConfigService.REDACTED + "\",\"host\":\"new-host\"}}");

		AiConfigService.restoreRedacted(original, proposed);

		assertEquals("{AES}AbCd==", proposed.get("password").asText());
		assertEquals("s3cret", proposed.get("gateway").get("secret").asText());
		assertEquals("new-host", proposed.get("gateway").get("host").asText());
	}

	@Test
	public void restoreLeavesMarkerWhenBaselineHasNoValue() throws Exception {
		JsonNode original = mapper.readTree("{}");
		JsonNode proposed = mapper.readTree("{\"password\":\"" + AiConfigService.REDACTED + "\"}");

		AiConfigService.restoreRedacted(original, proposed);

		assertEquals(AiConfigService.REDACTED, proposed.get("password").asText());
	}

	@Test
	public void validateReportsSchemaViolations() throws Exception {
		JsonNode schema = mapper.readTree(
				"{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\"}},\"required\":[\"count\"]}");

		List<String> good = AiConfigService.validate(schema, mapper.readTree("{\"count\":3}"));
		assertTrue(good.isEmpty());

		List<String> bad = AiConfigService.validate(schema, mapper.readTree("{\"count\":\"three\"}"));
		assertFalse(bad.isEmpty());
	}

	@Test
	public void extractJsonToleratesFencesAndProse() throws Exception {
		JsonNode a = AiConfigService.extractJson("```json\n{\"a\":1}\n```");
		assertEquals(1, a.get("a").asInt());

		JsonNode b = AiConfigService.extractJson("Here is the config:\n{\"b\":2}\nDone.");
		assertEquals(2, b.get("b").asInt());

		assertThrows(IllegalStateException.class, () -> AiConfigService.extractJson("no json here"));
	}

	@Test
	public void promptCarriesSchemaConfigAndInstruction() {
		String prompt = AiConfigService.buildPrompt("{\"s\":1}", "{\"c\":2}", "add a queue");
		assertTrue(prompt.contains("{\"s\":1}"));
		assertTrue(prompt.contains("{\"c\":2}"));
		assertTrue(prompt.contains("add a queue"));
	}

	@Test
	public void plaintextKeyStripsCleartextPrefixOnly() {
		assertEquals("sk-abc", AiConfigService.plaintextKey("{CLEARTEXT}sk-abc"));
		assertEquals("sk-abc", AiConfigService.plaintextKey("sk-abc"));
	}
}
