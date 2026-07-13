package org.vorpal.blade.framework.v2.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Lightweight smoke driver for [FormUriPreview] →
/// [SettingsManager#generateSchemaNode] `x-uri-preview` emission. Run with:
///
///     mvn -pl libs/framework -am test-compile
///     java -cp libs/framework/target/test-classes:libs/framework/target/classes:<deps> \
///          org.vorpal.blade.framework.v2.config.FormUriPreviewSmokeTest
///
/// No JUnit dependency. Each `check` either prints `PASS` or records a failure
/// and exits non-zero so the wrapper script can detect it.
public final class FormUriPreviewSmokeTest {
	private static int passed;
	private static int failed;

	@FormUriPreview
	public static class AnnotatedConfig {
		public String host;
	}

	public static class PlainConfig {
		public String host;
	}

	public static void main(String[] args) {
		ObjectMapper mapper = new ObjectMapper();

		JsonNode annotated = SettingsManager.generateSchemaNode(AnnotatedConfig.class, mapper);
		check("@FormUriPreview emits x-uri-preview=true", annotated.path("x-uri-preview").asBoolean(false));

		JsonNode plain = SettingsManager.generateSchemaNode(PlainConfig.class, mapper);
		check("absent @FormUriPreview emits no x-uri-preview", !plain.has("x-uri-preview"));

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
