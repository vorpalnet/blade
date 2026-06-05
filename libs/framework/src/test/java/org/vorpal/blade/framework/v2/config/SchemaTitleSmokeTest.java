package org.vorpal.blade.framework.v2.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Lightweight smoke driver for [SchemaTitle] →
/// [SettingsManager#generateSchemaNode] title emission. Run with:
///
///     mvn -pl libs/framework -am test-compile
///     java -cp libs/framework/target/test-classes:libs/framework/target/classes:<deps> \
///          org.vorpal.blade.framework.v2.config.SchemaTitleSmokeTest
///
/// No JUnit dependency. Each `check` either prints `PASS` or throws and
/// exits non-zero so the wrapper script can detect failure.
public final class SchemaTitleSmokeTest {
	private static int passed;
	private static int failed;

	@SchemaTitle("Titled Sample")
	public static class TitledConfig {
		public String name;
	}

	public static class UntitledConfig {
		public String name;
	}

	public static void main(String[] args) {
		ObjectMapper mapper = new ObjectMapper();

		JsonNode titled = SettingsManager.generateSchemaNode(TitledConfig.class, mapper);
		check("@SchemaTitle emits title keyword",
				"Titled Sample".equals(titled.path("title").asText()));

		JsonNode untitled = SettingsManager.generateSchemaNode(UntitledConfig.class, mapper);
		check("absent @SchemaTitle emits no title", !untitled.has("title"));

		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) {
			System.exit(1);
		}
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
