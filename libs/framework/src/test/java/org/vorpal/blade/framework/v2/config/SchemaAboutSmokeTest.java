package org.vorpal.blade.framework.v2.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

/// Lightweight smoke driver for [SchemaAbout] →
/// [SettingsManager#generateSchemaNode] root-identity emission, including the
/// `@Inherited` override behavior. Run with:
///
///     mvn -pl libs/framework -am test-compile
///     java -cp libs/framework/target/test-classes:libs/framework/target/classes:<deps> \
///          org.vorpal.blade.framework.v2.config.SchemaAboutSmokeTest
///
/// No JUnit dependency. Each `check` either prints `PASS` or records a failure
/// and exits non-zero so the wrapper script can detect it.
public final class SchemaAboutSmokeTest {
	private static int passed;
	private static int failed;

	@SchemaAbout(name = "Widget", tagline = "Does widgets", description = "A long widget description.")
	public static class FullConfig {
		public String value;
	}

	@SchemaAbout(name = "NameOnly")
	public static class NameOnlyConfig {
		public String value;
	}

	public static class PlainConfig {
		public String value;
	}

	/// Subclass of a @SchemaAbout class that does NOT redeclare it — must
	/// inherit the base's identity (@Inherited).
	public static class InheritingConfig extends FullConfig {
	}

	/// Subclass that redeclares @SchemaAbout — must override the base's.
	@SchemaAbout(name = "Override", tagline = "New tagline")
	public static class OverridingConfig extends FullConfig {
	}

	public static void main(String[] args) {
		ObjectMapper mapper = new ObjectMapper();

		JsonNode full = SettingsManager.generateSchemaNode(FullConfig.class, mapper);
		check("name emits root title", "Widget".equals(full.path("title").asText()));
		check("tagline emits x-tagline", "Does widgets".equals(full.path("x-tagline").asText()));
		check("description emits root description",
				"A long widget description.".equals(full.path("description").asText()));

		JsonNode nameOnly = SettingsManager.generateSchemaNode(NameOnlyConfig.class, mapper);
		check("name-only emits title", "NameOnly".equals(nameOnly.path("title").asText()));
		check("empty tagline omitted", !nameOnly.has("x-tagline"));
		check("empty description omitted", !nameOnly.has("description"));

		JsonNode plain = SettingsManager.generateSchemaNode(PlainConfig.class, mapper);
		check("absent @SchemaAbout emits no title", !plain.has("title"));
		check("absent @SchemaAbout emits no x-tagline", !plain.has("x-tagline"));

		JsonNode inherited = SettingsManager.generateSchemaNode(InheritingConfig.class, mapper);
		check("@Inherited: subclass inherits base identity",
				"Widget".equals(inherited.path("title").asText())
						&& "Does widgets".equals(inherited.path("x-tagline").asText()));

		JsonNode overriding = SettingsManager.generateSchemaNode(OverridingConfig.class, mapper);
		check("@Inherited: subclass overrides base name",
				"Override".equals(overriding.path("title").asText()));
		check("@Inherited: override replaces base tagline",
				"New tagline".equals(overriding.path("x-tagline").asText()));
		check("@Inherited: whole-annotation override drops base description",
				!overriding.has("description"));

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
