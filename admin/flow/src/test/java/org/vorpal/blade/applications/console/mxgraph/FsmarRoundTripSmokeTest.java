package org.vorpal.blade.applications.console.mxgraph;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Smoke-test driver for the FSMAR 3 ⇄ mxGraph round trip. The contract under
/// test is **no silent loss**: a full-featured FSMAR 3 config — state-level
/// selectors (regex/json/table with table data), when conditions, region,
/// routeModifier, ordered same-method transitions, and unknown fields at
/// every level — must survive import → export byte-for-byte semantically
/// (JsonNode equality). Also covers the two named-rejection paths (transition
/// without `next` on import; obsolete selectorGroup model on export) and the
/// semantic validator.
///
/// Same `main()` convention as the framework smoke tests; exits non-zero on
/// the first failed expectation.
///
/// ```
/// java -cp <flow classes>:<flow test-classes>:<framework classes>:<jackson>:<javaee-api> \
///   org.vorpal.blade.applications.console.mxgraph.FsmarRoundTripSmokeTest
/// ```
public class FsmarRoundTripSmokeTest {

	private static int failures = 0;
	private static final ObjectMapper mapper = new ObjectMapper();

	// A config exercising every feature the editor must not lose:
	//  - root extras (about, logging, customTopLevel)
	//  - "null" state with selectors (regex + table-with-data + unknown field)
	//  - trigger-level unknown field, transition-level unknown field
	//  - two same-method transitions (order is semantic: first match wins)
	//  - when / subscriber / region / routes-with-templates / routeModifier
	//  - xml selector with namespaces, json selector
	//  - states with empty triggers
	private static final String FULL_CONFIG = "{"
			+ "\"about\": {\"vendor\": \"Vorpal\"},"
			+ "\"logging\": {\"level\": \"FINE\"},"
			+ "\"customTopLevel\": {\"x\": 1},"
			+ "\"defaultApplication\": \"b2bua\","
			+ "\"states\": {"
			+ "  \"null\": {"
			+ "    \"selectors\": ["
			+ "      {\"type\":\"regex\",\"id\":\"To\",\"description\":\"to parts\","
			+ "       \"attribute\":\"To\",\"pattern\":\"sips?:(?<user>[^@]+)@(?<host>[^;>]+)\","
			+ "       \"expression\":\"${user}@${host}\"},"
			+ "      {\"type\":\"table\",\"id\":\"tier\",\"futureField\":true,"
			+ "       \"table\":{\"keyExpression\":\"${realmId}\","
			+ "                  \"translations\":{\"realm_001\":{\"customerId\":\"customer_a\"}}}}"
			+ "    ],"
			+ "    \"triggers\": {"
			+ "      \"INVITE\": {"
			+ "        \"transitions\": ["
			+ "          {\"id\":\"INV-1\",\"when\":\"${tier} == 'gold'\",\"next\":\"screening\","
			+ "           \"subscriber\":\"From\",\"region\":\"ORIGINATING\","
			+ "           \"routes\":[\"sip:${To.user}@proxy\"],\"routeModifier\":\"ROUTE_BACK\","
			+ "           \"customTx\":\"keep-me\"},"
			+ "          {\"id\":\"INV-2\",\"next\":\"b2bua\"}"
			+ "        ],"
			+ "        \"customTriggerField\": \"keep-me-too\""
			+ "      },"
			+ "      \"REGISTER\": {\"transitions\":[{\"id\":\"REG-1\",\"next\":\"registrar\"}]}"
			+ "    },"
			+ "    \"customStateField\": {\"nested\": true}"
			+ "  },"
			+ "  \"screening\": {"
			+ "    \"selectors\": ["
			+ "      {\"type\":\"json\",\"id\":\"verdict\",\"attribute\":\"$.actionDirective\"},"
			+ "      {\"type\":\"xml\",\"id\":\"pidf\",\"attribute\":\"//p:tuple/p:status\","
			+ "       \"namespaces\":{\"p\":\"urn:ietf:params:xml:ns:pidf\"}}"
			+ "    ],"
			+ "    \"triggers\": {\"INVITE\": {\"transitions\":[{\"id\":\"SCR-1\",\"next\":\"null\"}]}}"
			+ "  },"
			+ "  \"b2bua\": {\"triggers\": {}},"
			+ "  \"registrar\": {\"triggers\": {}}"
			+ "}}";

	public static void main(String[] args) throws Exception {
		roundTripPreservesEverything();
		transitionOrderSurvivesRoundTrip();
		tierDispatchSurvivesRoundTrip();
		importRejectsTransitionWithoutNext();
		exportRejectsObsoleteSelectorGroups();
		validatorFlagsRealProblems();
		validatorFlagsShadowing();
		validatorPassesCleanConfig();

		if (failures > 0) {
			System.err.println("FAILED: " + failures + " expectation(s)");
			System.exit(1);
		}
		System.out.println("PASS: FSMAR round-trip smoke tests");
	}

	// ----- tests --------------------------------------------------------------

	static void roundTripPreservesEverything() throws Exception {
		JsonNode original = mapper.readTree(FULL_CONFIG);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);

		expect(original.equals(exported),
				"round trip must preserve every field", () -> diff(original, exported));
	}

	static void transitionOrderSurvivesRoundTrip() throws Exception {
		// Three same-method transitions; order is first-match-wins semantics.
		String cfg = "{\"states\":{\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "{\"id\":\"A\",\"next\":\"x\"},"
				+ "{\"id\":\"B\",\"next\":\"y\"},"
				+ "{\"id\":\"C\",\"next\":\"x\"}]}}}}}";
		JsonNode original = mapper.readTree(cfg);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);

		JsonNode txs = exported.path("states").path("null")
				.path("triggers").path("INVITE").path("transitions");
		String order = txs.path(0).path("id").asText() + txs.path(1).path("id").asText()
				+ txs.path(2).path("id").asText();
		expect("ABC".equals(order), "transition order must survive (got " + order + ")", null);
	}

	static void tierDispatchSurvivesRoundTrip() throws Exception {
		// The gold/silver/bronze dispatch pattern the editor generates:
		// mutually exclusive equality conditions plus an unconditional
		// default, order carried by seq. Must round-trip exactly.
		String cfg = "{\"defaultApplication\":\"b2bua\",\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "{\"when\":\"${tier} == 'gold'\",\"next\":\"premium-screening\"},"
				+ "{\"when\":\"${tier} == 'silver'\",\"next\":\"screening\"},"
				+ "{\"when\":\"${tier} == 'bronze'\",\"next\":\"screening\"},"
				+ "{\"next\":\"b2bua\"}]}}},"
				+ "\"premium-screening\":{\"triggers\":{}},"
				+ "\"screening\":{\"triggers\":{}},"
				+ "\"b2bua\":{\"triggers\":{}}}}";
		JsonNode original = mapper.readTree(cfg);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);
		expect(original.equals(exported),
				"tier dispatch must round-trip exactly", () -> diff(original, exported));

		// And it must validate clean: mutually exclusive conditions with the
		// default LAST produce no shadowing findings.
		List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(), infos = new ArrayList<>();
		new FsmarValidateServlet().validate(original, errors, warnings, infos);
		expect(errors.isEmpty() && warnings.isEmpty(),
				"dispatch family must validate clean, got errors=" + errors + " warnings=" + warnings, null);
	}

	static void validatorFlagsShadowing() throws Exception {
		// Unconditional transition FIRST: everything after it is unreachable.
		// Plus a duplicate-when pair.
		String cfg = "{\"states\":{\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "{\"id\":\"T0\",\"next\":\"a\"},"
				+ "{\"id\":\"T1\",\"when\":\"${tier} == 'gold'\",\"next\":\"b\"},"
				+ "{\"id\":\"T2\",\"when\":\"${tier} == 'gold'\",\"next\":\"c\"}"
				+ "]}}}}}";
		List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(), infos = new ArrayList<>();
		new FsmarValidateServlet().validate(mapper.readTree(cfg), errors, warnings, infos);
		expect(any(warnings, "transitions[1] is unreachable"),
				"must flag transitions after an unconditional one: " + warnings, null);
		expect(any(warnings, "transitions[2] is unreachable"),
				"must flag ALL transitions after an unconditional one: " + warnings, null);

		// Duplicate-when without the unconditional shadow in front
		String cfg2 = "{\"states\":{\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "{\"id\":\"T0\",\"when\":\"${tier} == 'gold'\",\"next\":\"a\"},"
				+ "{\"id\":\"T1\",\"when\":\"${tier} == 'gold'\",\"next\":\"b\"}"
				+ "]}}}}}";
		errors.clear(); warnings.clear(); infos.clear();
		new FsmarValidateServlet().validate(mapper.readTree(cfg2), errors, warnings, infos);
		expect(any(warnings, "transitions[1] is shadowed"),
				"must flag duplicate-when shadowing: " + warnings, null);
	}

	static void importRejectsTransitionWithoutNext() throws Exception {
		String cfg = "{\"states\":{\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "{\"id\":\"BAD\"}]}}}}}";
		try {
			new FsmarImportServlet().buildMxGraphXml(mapper.readTree(cfg));
			expect(false, "import must reject a transition without next", null);
		} catch (IllegalArgumentException e) {
			expect(e.getMessage().contains("transitions[0]") && e.getMessage().contains("next"),
					"rejection must name the location (got: " + e.getMessage() + ")", null);
		}
	}

	static void exportRejectsObsoleteSelectorGroups() throws Exception {
		String xml = "<mxGraphModel><root>"
				+ "<Transition label=\"INVITE\"><selectorGroup/>"
				+ "<mxCell id=\"9\" edge=\"1\" source=\"2\" target=\"3\"/></Transition>"
				+ "</root></mxGraphModel>";
		try {
			new FsmarExportServlet().buildFsmarJson(xml);
			expect(false, "export must reject the obsolete selectorGroup model", null);
		} catch (IllegalArgumentException e) {
			expect(e.getMessage().contains("selector-group"),
					"rejection must explain the obsolete model (got: " + e.getMessage() + ")", null);
		}
	}

	static void validatorFlagsRealProblems() throws Exception {
		String cfg = "{\"states\":{"
				+ "\"null\":{\"selectors\":[{\"type\":\"regex\",\"id\":\"bad\",\"pattern\":\"(unclosed\"}],"
				+ "  \"triggers\":{\"INVITE\":{\"transitions\":["
				+ "    {\"id\":\"T1\",\"when\":\"${x} === broken ((\",\"next\":\"app1\"},"
				+ "    {\"id\":\"T2\",\"next\":\"app2\",\"region\":\"SIDEWAYS\"},"
				+ "    {\"id\":\"T3\",\"next\":\"app3\",\"regoin\":\"NEUTRAL\"}"
				+ "  ]}}}}}";
		List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(), infos = new ArrayList<>();
		new FsmarValidateServlet().validate(mapper.readTree(cfg), errors, warnings, infos);

		expect(any(errors, "pattern does not compile"), "must flag bad regex: " + errors, null);
		expect(any(errors, ".when does not parse"), "must flag bad when: " + errors, null);
		expect(any(errors, "SIDEWAYS"), "must flag bad region: " + errors, null);
		expect(any(warnings, "regoin"), "must warn on typo'd field: " + warnings, null);
	}

	static void validatorPassesCleanConfig() throws Exception {
		List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(), infos = new ArrayList<>();
		new FsmarValidateServlet().validate(mapper.readTree(FULL_CONFIG), errors, warnings, infos);
		expect(errors.isEmpty(), "full config must validate clean, got errors: " + errors, null);
		// FULL_CONFIG deliberately carries unknown fields — they must surface
		// as warnings (typo guard), never errors (they round-trip safely).
		expect(any(warnings, "customTx"), "unknown tx field should warn: " + warnings, null);
	}

	// ----- plumbing ------------------------------------------------------------

	private static boolean any(List<String> list, String fragment) {
		for (String s : list) {
			if (s.contains(fragment)) return true;
		}
		return false;
	}

	private static void expect(boolean ok, String what, java.util.function.Supplier<String> detail) {
		if (!ok) {
			failures++;
			System.err.println("FAIL: " + what);
			if (detail != null) {
				System.err.println(detail.get());
			}
		} else {
			System.out.println("ok: " + what);
		}
	}

	private static String diff(JsonNode a, JsonNode b) {
		try {
			return "--- original ---\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(a)
					+ "\n--- exported ---\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(b);
		} catch (Exception e) {
			return "(diff unavailable: " + e + ")";
		}
	}

}
