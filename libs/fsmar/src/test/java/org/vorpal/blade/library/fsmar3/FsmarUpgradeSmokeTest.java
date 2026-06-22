package org.vorpal.blade.library.fsmar3;

import org.vorpal.blade.framework.v3.fsmar.AppRouterConfiguration;
import org.vorpal.blade.framework.v3.fsmar.Fsmar2Converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Smoke test for the FSMAR config-load upgrade seam ([FsmarSettings]): version/
/// shape detection ([FsmarSettings#isLegacy]) plus the read-time upgrade a
/// legacy `fsmar2.json` / pre-3 tree goes through before the framework
/// deserializes it. Mirrors exactly what `FsmarSettings.readConfigTree` does to a
/// detected-legacy tree (convert → toValidatedJson → readTree → deserialize),
/// without the WLS/MBean machinery a live Settings needs.
///
/// Run via `main`, like the other FSMAR smoke tests.
public final class FsmarUpgradeSmokeTest {
	private static int passed;
	private static int failed;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(String[] args) throws Exception {
		testDetection();
		testV2TreeUpgrades();
		testV3TreePassesThrough();
		summary();
	}

	/// isLegacy: FSMAR 2 shape (previous, no states) or explicit version < 3.
	private static void testDetection() throws Exception {
		check("v2-shaped (previous, no states) is legacy",
				FsmarSettings.isLegacy(tree("{\"previous\":{\"null\":{}}}")));
		check("explicit version 2 is legacy",
				FsmarSettings.isLegacy(tree("{\"version\":2,\"states\":{}}")));
		check("v3 (states, no version) is NOT legacy",
				!FsmarSettings.isLegacy(tree("{\"states\":{\"null\":{}}}")));
		check("explicit version 3 is NOT legacy",
				!FsmarSettings.isLegacy(tree("{\"version\":3,\"states\":{}}")));
		check("empty/blank tree is NOT legacy",
				!FsmarSettings.isLegacy(tree("{}")));
	}

	/// A real FSMAR 2 config tree upgrades to a working v3 AppRouterConfiguration
	/// — the exact transform FsmarSettings.readConfigTree applies on load.
	private static void testV2TreeUpgrades() throws Exception {
		String fsmar2 = "{"
				+ "\"defaultApplication\": \"b2bua\","
				+ "\"previous\": { \"null\": { \"triggers\": {"
				+ "  \"INVITE\": { \"transitions\": [ { \"id\": \"INV01\", \"next\": \"recorder\","
				+ "    \"condition\": { \"Contact\": [ { \"host\": \"192.0.2.71\" } ] } } ] }"
				+ "} } } }";
		JsonNode v2 = tree(fsmar2);
		check("detected as legacy", FsmarSettings.isLegacy(v2));

		// Same steps as FsmarSettings.readConfigTree on a legacy tree:
		Fsmar2Converter.Result result = Fsmar2Converter.convert(v2);
		String json = Fsmar2Converter.toValidatedJson(result);
		JsonNode upgraded = MAPPER.readTree(json);

		check("upgraded tree is no longer legacy", !FsmarSettings.isLegacy(upgraded));
		check("upgraded tree has v3 states", upgraded.has("states") && !upgraded.has("previous"));

		// Deserializes into the real v3 config the engine loads.
		AppRouterConfiguration cfg = MAPPER.readValue(json, AppRouterConfiguration.class);
		check("default app carried", "b2bua".equals(cfg.getDefaultApplication()));
		check("null state present", cfg.getStates().get("null") != null);
		check("INVITE transition routes to recorder", "recorder".equals(cfg.getStates().get("null")
				.getTriggers().get("INVITE").getTransitions().get(0).getNext()));
	}

	/// A current v3 tree is untouched (isLegacy false → readConfigTree returns
	/// it verbatim).
	private static void testV3TreePassesThrough() throws Exception {
		String v3json = "{\"version\":3,\"defaultApplication\":\"b2bua\","
				+ "\"states\":{\"null\":{\"triggers\":{}}}}";
		JsonNode v3 = tree(v3json);
		check("v3 tree not legacy (passes through)", !FsmarSettings.isLegacy(v3));
		AppRouterConfiguration cfg = MAPPER.readValue(v3json, AppRouterConfiguration.class);
		check("v3 deserializes", "b2bua".equals(cfg.getDefaultApplication()));
	}

	// ------------------------------------------------------------------

	private static JsonNode tree(String json) throws Exception {
		return MAPPER.readTree(json);
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}

	private static void summary() {
		System.out.println("FsmarUpgradeSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) {
			System.exit(1);
		}
	}
}
