package org.vorpal.blade.framework.v2.config;

import com.fasterxml.jackson.databind.ObjectMapper;

/// Lightweight smoke driver for [SettingsManager#configureMapper] unknown-key
/// tolerance: a config JSON carrying keys the schema no longer (or does not
/// yet) define must still deserialize, so a removed property never bricks an
/// app on deploy. Run with:
///
///     mvn -pl libs/framework -am test-compile
///     java -cp libs/framework/target/test-classes:libs/framework/target/classes:<deps> \
///          org.vorpal.blade.framework.v2.config.UnknownKeySmokeTest
///
/// No JUnit dependency. Each `check` either prints `PASS` or throws and
/// exits non-zero so the wrapper script can detect failure.
public final class UnknownKeySmokeTest {
	private static int passed;
	private static int failed;

	public static class SampleConfig {
		public String name;
		public Integer kept = 1;
	}

	public static void main(String[] args) throws Exception {
		// The mapper exactly as SettingsManager.build() configures it.
		ObjectMapper configured = new ObjectMapper();
		new SettingsManager<SampleConfig>().configureMapper(configured);

		SampleConfig cfg = configured.readValue(
				"{\"name\":\"x\",\"removedProperty\":true,\"another\":{\"nested\":1}}",
				SampleConfig.class);
		check("unknown keys ignored, known keys populated",
				"x".equals(cfg.name) && Integer.valueOf(1).equals(cfg.kept));

		// Contrast: a bare Jackson mapper must still fail, proving the test
		// exercises configureMapper rather than a Jackson default.
		boolean bareFails;
		try {
			new ObjectMapper().readValue("{\"removedProperty\":true}", SampleConfig.class);
			bareFails = false;
		} catch (Exception e) {
			bareFails = true;
		}
		check("bare mapper still rejects unknown keys (contrast)", bareFails);

		// Realistic case: a framework config class with a stale key.
		RouterConfig rc = configured.readValue(
				"{\"heldStreamReaperIntervalSeconds\":60}", RouterConfig.class);
		check("RouterConfig tolerates a stale key", rc != null);

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
