package org.vorpal.blade.framework.v2.config;

/// Lightweight smoke driver for [SettingsManager#flatten] — the canonical
/// context-path → config-name mapping (admin "/blade/crud" → "blade-crud",
/// service "/crud" → "crud"). Run with:
///
///     mvn -pl libs/framework -am test-compile
///     java -cp libs/framework/target/test-classes:libs/framework/target/classes:<deps> \
///          org.vorpal.blade.framework.v2.config.FlattenSmokeTest
///
/// No JUnit dependency. Each `check` either prints `PASS` or throws and
/// exits non-zero so the wrapper script can detect failure.
public final class FlattenSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		// Service context path: leading slash stripped, nothing else changes.
		check("/crud -> crud", "crud".equals(SettingsManager.flatten("/crud")));

		// Admin context path: flattened with '-'.
		check("/blade/crud -> blade-crud", "blade-crud".equals(SettingsManager.flatten("/blade/crud")));

		// No leading slash (weblogic.xml style) flattens the same way.
		check("blade/analytics -> blade-analytics",
				"blade-analytics".equals(SettingsManager.flatten("blade/analytics")));

		// Already-flat names pass through, hyphens included.
		check("crud-editor passthrough", "crud-editor".equals(SettingsManager.flatten("crud-editor")));

		// Idempotent: flattening a flattened name is a no-op.
		check("idempotent", "blade-crud".equals(SettingsManager.flatten(SettingsManager.flatten("/blade/crud"))));

		// null/empty returned as-is (caller handles fallback).
		check("null passthrough", SettingsManager.flatten(null) == null);
		check("empty passthrough", "".equals(SettingsManager.flatten("")));

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
