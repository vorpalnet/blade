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
	public void aChatAnswerIsAReplyAndAConfigOrAReplyAlone() throws Exception {
		JsonNode change = mapper.readTree("{\"reply\":\"Added a queue.\",\"config\":{\"queues\":[]}}");
		assertEquals("Added a queue.", AiConfigService.replyOf(change));
		assertTrue(AiConfigService.configOf(change).has("queues"));
		JsonNode question = mapper.readTree("{\"reply\":\"It holds 10 calls.\",\"config\":null}");
		assertEquals(null, AiConfigService.configOf(question));
		// An answer in the older form, the document alone, is the config.
		JsonNode bare = mapper.readTree("{\"queues\":[]}");
		assertEquals(null, AiConfigService.replyOf(bare));
		assertTrue(AiConfigService.configOf(bare).has("queues"));
	}

	@Test
	public void theConversationKeepsTheLastTurnsInOrder() {
		String c = AiConfigService.conversation("[{\"role\":\"user\",\"text\":\"add a queue\"},"
				+ "{\"role\":\"assistant\",\"text\":\"Added support.\"}]");
		assertEquals("Operator: add a queue\nYou: Added support.\n", c);
		assertEquals("", AiConfigService.conversation(null));
		assertTrue(AiConfigService.buildPrompt("{}", "{}", c, "make it 20").contains("Operator: add a queue"));
	}

	@Test
	public void aPasswordInsideAPolymorphicVariantIsRedacted() throws Exception {
		JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"pipeline\":{\"type\":\"array\","
				+ "\"items\":{\"anyOf\":[{\"$ref\":\"#/$defs/Sip\"},{\"$ref\":\"#/$defs/Rest\"}]}}},"
				+ "\"$defs\":{\"Sip\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}},"
				+ "\"Rest\":{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},"
				+ "\"password\":{\"type\":\"string\",\"format\":\"password\"}}}}}");
		JsonNode config = mapper.readTree("{\"pipeline\":[{\"id\":\"s\"},{\"url\":\"http://x\",\"password\":\"plain\"}]}");
		JsonNode out = AiConfigService.redactSecrets(config, schema);
		assertEquals(AiConfigService.REDACTED, out.path("pipeline").path(1).path("password").asText());
		assertEquals("http://x", out.path("pipeline").path(1).path("url").asText());
	}

	@Test
	public void aSecretFollowsItsItemNotItsPlace() throws Exception {
		JsonNode original = mapper.readTree("{\"endpoints\":[{\"name\":\"a\",\"password\":\"{AES}A\"},"
				+ "{\"name\":\"b\",\"password\":\"{AES}B\"}]}");
		// "Remove the first endpoint": b moves to place 0.
		JsonNode proposed = mapper.readTree("{\"endpoints\":[{\"name\":\"b\",\"password\":\"{REDACTED}\"}]}");
		AiConfigService.restoreRedacted(original, proposed);
		assertEquals("{AES}B", proposed.path("endpoints").path(0).path("password").asText());
		// A new item's marker has nothing to restore from, and is reported.
		JsonNode added = mapper.readTree("{\"endpoints\":[{\"name\":\"c\",\"password\":\"{REDACTED}\"}]}");
		AiConfigService.restoreRedacted(original, added);
		assertEquals(List.of("endpoints[0].password"), AiConfigService.unrestoredSecrets(added, ""));
	}

	@Test
	public void anOverlayIsCheckedWithoutRequired() throws Exception {
		JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"logging\":{\"type\":\"object\","
				+ "\"required\":[\"directory\",\"loggingLevel\"],\"properties\":{\"directory\":{\"type\":\"string\"},"
				+ "\"loggingLevel\":{\"type\":\"string\"},\"required\":{\"type\":\"boolean\"}}}}}");
		JsonNode overlay = mapper.readTree("{\"logging\":{\"loggingLevel\":\"FINEST\"}}");
		assertFalse(AiConfigService.validate(schema, overlay).isEmpty(), "a whole document needs both");
		assertTrue(AiConfigService.validate(AiConfigService.withoutRequired(schema), overlay).isEmpty());
		// A property that happens to be named "required" is kept.
		assertTrue(AiConfigService.withoutRequired(schema).path("properties").path("logging").path("properties").has("required"));
	}

	@Test
	public void aSecretThatIsAListItemIsRedacted() throws Exception {
		JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"keys\":{\"type\":\"array\","
				+ "\"items\":{\"type\":\"string\",\"format\":\"password\"}},\"names\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}");
		JsonNode config = mapper.readTree("{\"keys\":[\"{CLEARTEXT}pw\",\"plain\"],\"names\":[\"a\",\"{AES256}xyz\"]}");
		JsonNode out = AiConfigService.redactSecrets(config, schema);
		assertEquals(AiConfigService.REDACTED, out.path("keys").path(0).asText());
		assertEquals(AiConfigService.REDACTED, out.path("keys").path(1).asText(), "a password-format item, even plain");
		assertEquals("a", out.path("names").path(0).asText());
		assertEquals(AiConfigService.REDACTED, out.path("names").path(1).asText(), "an encrypted value anywhere");
	}

	@Test
	public void aBarePasswordFromTheJsonTabIsMarkedForEncryption() throws Exception {
		JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"apiKey\":{\"type\":\"string\",\"format\":\"password\"},"
				+ "\"token\":{\"type\":\"string\",\"format\":\"password\"},\"ref\":{\"type\":\"string\",\"format\":\"password\"},"
				+ "\"name\":{\"type\":\"string\"}}}");
		JsonNode config = mapper.readTree("{\"apiKey\":\"sk-123\",\"token\":\"{AES256}abc\",\"ref\":\"${apiKey}\",\"name\":\"plain\"}");
		AiConfigService.markPasswordsForEncryption(config, schema, schema);
		assertEquals("{CLEARTEXT}sk-123", config.path("apiKey").asText());
		assertEquals("{AES256}abc", config.path("token").asText(), "already encrypted");
		assertEquals("${apiKey}", config.path("ref").asText(), "a reference, not a secret");
		assertEquals("plain", config.path("name").asText(), "not a password field");
	}

	@Test
	public void victoolsWrappersAreFollowedToTheirBase() throws Exception {
		// The shape BLADE's generated schemas use: Map(String,Connector) -> anyOf of
		// "-2" wrappers ({$ref: base "-1", properties: {type: const}}), and an
		// inline anyOf for authentication whose variants are wrappers again.
		JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"pipeline\":{\"$ref\":\"#/$defs/Map\"}},"
				+ "\"$defs\":{\"Map\":{\"type\":\"object\",\"additionalProperties\":{\"$ref\":\"#/$defs/Connector\"}},"
				+ "\"Connector\":{\"anyOf\":[{\"$ref\":\"#/$defs/Rest-2\"}]},"
				+ "\"Rest-2\":{\"$ref\":\"#/$defs/Rest-1\",\"properties\":{\"type\":{\"const\":\"rest\"}}},"
				+ "\"Rest-1\":{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},"
				+ "\"auth\":{\"anyOf\":[{\"$ref\":\"#/$defs/Basic-2\"}]}}},"
				+ "\"Basic-2\":{\"$ref\":\"#/$defs/Basic-1\",\"properties\":{\"type\":{\"const\":\"basic\"}}},"
				+ "\"Basic-1\":{\"type\":\"object\",\"properties\":{\"username\":{\"type\":\"string\"},"
				+ "\"password\":{\"type\":\"string\",\"format\":\"password\"}}}}}");
		JsonNode config = mapper.readTree("{\"pipeline\":{\"r\":{\"type\":\"rest\",\"url\":\"http://x\","
				+ "\"auth\":{\"type\":\"basic\",\"username\":\"u\",\"password\":\"plain\"}}}}");
		JsonNode redacted = AiConfigService.redactSecrets(config.deepCopy(), schema);
		assertEquals(AiConfigService.REDACTED, redacted.path("pipeline").path("r").path("auth").path("password").asText());
		AiConfigService.markPasswordsForEncryption(config, schema, schema);
		assertEquals("{CLEARTEXT}plain", config.path("pipeline").path("r").path("auth").path("password").asText());
		assertEquals("u", config.path("pipeline").path("r").path("auth").path("username").asText());
	}

	@Test
	public void aPushSplitsOnNewlinesOnly() {
		// U+2028 inside a JSON string is not a line break for the push.
		java.util.List<String> lines = ValidationAPI.lines("{\r\n  \"t\": \"a\u2028b\u0085c\"\n}\n");
		assertEquals(List.of("{", "  \"t\": \"a\u2028b\u0085c\"", "}"), lines);
	}

	@Test
	public void secretsFollowTheirItemThroughSharedIdsAndReorders() throws Exception {
		JsonNode original = mapper.readTree("{\"pipeline\":[{\"id\":\"crm\",\"url\":\"http://a\",\"password\":\"{AES}A\"},"
				+ "{\"id\":\"crm\",\"url\":\"http://b\",\"password\":\"{AES}B\"}]}");
		// Shared id: each item still gets its own secret, by everything else it holds.
		JsonNode same = mapper.readTree("{\"pipeline\":[{\"id\":\"crm\",\"url\":\"http://a\",\"password\":\"{REDACTED}\"},"
				+ "{\"id\":\"crm\",\"url\":\"http://b\",\"password\":\"{REDACTED}\"}]}");
		AiConfigService.restoreRedacted(original, same);
		assertEquals("{AES}B", same.path("pipeline").path(1).path("password").asText());
		// Reordered, no ids: secrets move with their items.
		JsonNode o2 = mapper.readTree("{\"l\":[{\"url\":\"http://a\",\"password\":\"{AES}A\"},{\"url\":\"http://b\",\"password\":\"{AES}B\"}]}");
		JsonNode p2 = mapper.readTree("{\"l\":[{\"url\":\"http://b\",\"password\":\"{REDACTED}\"},{\"url\":\"http://a\",\"password\":\"{REDACTED}\"}]}");
		AiConfigService.restoreRedacted(o2, p2);
		assertEquals("{AES}B", p2.path("l").path(0).path("password").asText());
		assertEquals("{AES}A", p2.path("l").path(1).path("password").asText());
		// Changed besides the secret, under a shared id: no guess; reported instead.
		JsonNode p3 = mapper.readTree("{\"pipeline\":[{\"id\":\"crm\",\"url\":\"http://c\",\"password\":\"{REDACTED}\"}]}");
		AiConfigService.restoreRedacted(original, p3);
		assertEquals(List.of("pipeline[0].password"), AiConfigService.unrestoredSecrets(p3, ""));
	}

	@Test
	public void aSecretNamedByItsKeyIsRedactedInAFreeFormMap() throws Exception {
		JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"headers\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}}}");
		JsonNode config = mapper.readTree("{\"headers\":{\"Authorization\":\"Bearer abc\",\"X-Api-Key\":\"k\",\"Accept\":\"application/json\"}}");
		JsonNode out = AiConfigService.redactSecrets(config, schema);
		assertEquals(AiConfigService.REDACTED, out.path("headers").path("Authorization").asText());
		assertEquals(AiConfigService.REDACTED, out.path("headers").path("X-Api-Key").asText());
		assertEquals("application/json", out.path("headers").path("Accept").asText());
	}

	/// Secrets the first rules missed: no "//" in the URL, a header written
	/// out, a name/value pair, a key ending in Pass, a marker kept in text.
	@Test
	public void lessObviousSecretsAreRedacted() throws Exception {
		assertTrue(AiConfigService.carriesCredential("jdbc:oracle:thin:scott/tiger@//db:1521/svc"));
		assertTrue(AiConfigService.carriesCredential("sip:alice:pw@example.test"));
		assertTrue(AiConfigService.carriesCredential("X-Api-Key: abc123"));
		assertTrue(AiConfigService.carriesCredential("Authorization: Token xyz"));
		assertTrue(!AiConfigService.carriesCredential("sip:alice@example.test:5060"));
		assertTrue(!AiConfigService.carriesCredential("Accept: */*"));
		assertTrue(AiConfigService.secretName("keystorePass"));
		assertTrue(AiConfigService.secretName("passphrase"));
		assertTrue(AiConfigService.secretName("preSharedKey"));
		assertTrue(!AiConfigService.secretName("bypass"));
		JsonNode out = AiConfigService.redactSecrets(mapper.readTree(
				"{\"h\":[{\"name\":\"X-Api-Key\",\"value\":\"abc123\"},{\"name\":\"Accept\",\"value\":\"*/*\"}]}"),
				mapper.readTree("{\"type\":\"object\"}"));
		assertEquals(AiConfigService.REDACTED, out.path("h").get(0).path("value").asText());
		assertEquals("*/*", out.path("h").get(1).path("value").asText());
		assertEquals(List.of("pw"), AiConfigService.unrestoredSecrets(mapper.readTree("{\"pw\":\"{REDACTED}!\"}"), ""));
	}

	@Test
	public void aKeyNamingWhereASecretGoesIsNotRedacted() {
		assertTrue(AiConfigService.secretName("accessToken"));
		assertTrue(AiConfigService.secretName("clientSecret"));
		assertTrue(!AiConfigService.secretName("tokenUrl"));
		assertTrue(!AiConfigService.secretName("apiKeyHeader"));
		assertTrue(!AiConfigService.secretName("passwordFile"));
		assertTrue(!AiConfigService.secretName("tokenTtl"));
	}

	@Test
	public void aCredentialInsideOrdinaryTextIsRedacted() throws Exception {
		assertTrue(AiConfigService.carriesCredential("https://svc:s3cr3t@host.example/x"));
		assertTrue(AiConfigService.carriesCredential("jdbc:sqlserver://h;user=u;password=p"));
		assertTrue(AiConfigService.carriesCredential("Authorization: Bearer abc.def.ghi"));
		assertTrue(AiConfigService.carriesCredential("Basic dXNlcjpwYXNz"));
		assertTrue(!AiConfigService.carriesCredential("https://host.example/x?y=1"));
		assertTrue(!AiConfigService.carriesCredential("sip:alice@example.test"));
		assertTrue(!AiConfigService.carriesCredential("Basic plan"));
		JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"headers\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"pwd\":{\"type\":\"string\"}}}");
		JsonNode out = AiConfigService.redactSecrets(mapper.readTree("{\"headers\":[\"Accept: */*\",\"Bearer abcdefgh123\"],\"pwd\":\"x\"}"), schema);
		assertEquals("Accept: */*", out.path("headers").get(0).asText());
		assertEquals(AiConfigService.REDACTED, out.path("headers").get(1).asText());
		assertEquals(AiConfigService.REDACTED, out.path("pwd").asText());
	}

	@Test
	public void aListOfSecretStringsIsRestoredByPlace() throws Exception {
		JsonNode original = mapper.readTree("{\"keys\":[\"{AES}a\",\"{AES}b\"],\"x\":1}");
		JsonNode proposed = mapper.readTree("{\"keys\":[\"{REDACTED}\",\"{REDACTED}\"],\"x\":2}");
		AiConfigService.restoreRedacted(original, proposed);
		assertEquals("{AES}a", proposed.path("keys").get(0).asText());
		assertEquals("{AES}b", proposed.path("keys").get(1).asText());
		assertEquals(List.of(), AiConfigService.unrestoredSecrets(proposed, ""));
	}

	@Test
	public void anItemMadeEqualToAnotherGetsNoOnesSecret() throws Exception {
		JsonNode original = mapper.readTree("{\"l\":[{\"host\":\"x\",\"password\":\"{AES}a\"},{\"host\":\"y\",\"password\":\"{AES}b\"}]}");
		// "point the second at host x": both now equal the first apart from secrets.
		JsonNode proposed = mapper.readTree("{\"l\":[{\"host\":\"x\",\"password\":\"{REDACTED}\"},{\"host\":\"x\",\"password\":\"{REDACTED}\"}]}");
		AiConfigService.restoreRedacted(original, proposed);
		assertEquals(2, AiConfigService.unrestoredSecrets(proposed, "").size());
	}

	@Test
	public void aSecretNamedKeyHoldingAListIsRedactedWhole() throws Exception {
		JsonNode schema = mapper.readTree("{\"type\":\"object\"}");
		JsonNode out = AiConfigService.redactSecrets(mapper.readTree("{\"tokens\":[\"abc\",\"def\"],\"credentials\":{\"svc\":\"pw\"}}"), schema);
		assertEquals(AiConfigService.REDACTED, out.path("tokens").get(1).asText());
		assertEquals(AiConfigService.REDACTED, out.path("credentials").path("svc").asText());
	}

	@Test
	public void plaintextKeyStripsCleartextPrefixOnly() {
		assertEquals("sk-abc", AiConfigService.plaintextKey("{CLEARTEXT}sk-abc"));
		assertEquals("sk-abc", AiConfigService.plaintextKey("sk-abc"));
	}
}
