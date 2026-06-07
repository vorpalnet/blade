package org.vorpal.blade.framework.v2.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Lightweight smoke driver for the [Configuration#getVersion] config schema
/// version field. Run with:
///
///     mvn -pl libs/framework -am test-compile
///     java -cp libs/framework/target/test-classes:libs/framework/target/classes:<deps> \
///          org.vorpal.blade.framework.v2.config.ConfigurationVersionSmokeTest
///
/// No JUnit dependency. Each `check` either prints `PASS` or throws and
/// exits non-zero so the wrapper script can detect failure.
public final class ConfigurationVersionSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Exception {
		ObjectMapper mapper = new ObjectMapper();

		// Pre-versioning file (no "version" key) reads back as 0.
		Configuration empty = mapper.readValue("{}", Configuration.class);
		check("absent version reads as 0", empty.getVersion() == 0);

		// An explicit version binds (guards against a read-only access
		// annotation ever silently blocking deserialization).
		Configuration v3 = mapper.readValue("{\"version\": 3}", Configuration.class);
		check("explicit version binds", v3.getVersion() == 3);

		// Serialization emits version (via the getter, so 0 even when unset)
		// and orders it first per @JsonPropertyOrder.
		JsonNode tree = mapper.valueToTree(new Configuration());
		check("fresh Configuration serializes version 0", tree.path("version").asInt(-1) == 0);
		check("version is ordered first", "version".equals(tree.fieldNames().next()));

		// Schema marks the field read-only for the Configurator form.
		JsonNode schema = SettingsManager.generateSchemaNode(Configuration.class, mapper);
		JsonNode versionNode = schema.path("properties").path("version");
		check("schema has version property", !versionNode.isMissingNode());
		check("schema marks version x-readonly",
				findVersionProperty(schema).path("x-readonly").asBoolean(false));

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) {
			System.exit(1);
		}
	}

	/// The version property may live inline under `properties` or behind a
	/// `$ref` into `$defs` (DEFINITIONS_FOR_ALL_OBJECTS) — resolve either.
	private static JsonNode findVersionProperty(JsonNode schema) {
		JsonNode node = schema.path("properties").path("version");
		if (node.has("$ref")) {
			String ref = node.get("$ref").asText(); // e.g. "#/$defs/Integer"
			JsonNode target = schema.at(ref.substring(1));
			// x-readonly is an instance attribute; it stays on the referring
			// node alongside $ref via allOf or inline extras.
			if (!target.isMissingNode() && node.size() == 1) {
				return target;
			}
		}
		return node;
	}

	private static void check(String label, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("PASS  " + label);
		} else {
			failed++;
			System.out.println("FAIL  " + label);
		}
	}
}
