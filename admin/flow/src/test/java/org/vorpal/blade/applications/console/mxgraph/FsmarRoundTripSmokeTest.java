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
		diagramPlacementsSurviveRoundTrip();
		multiIngressRoundTrip();
		dispatchGeneratedAndAbsorbed();
		egressRoundTrip();
		routeBackEgressRoundTrip();
		stateIdAndAppRoundTrip();
		bareConfigGetsDiagramOnExport();
		cellIdsLiveOnWrappers();
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

		// Export always emits a diagram section from the vertex geometry —
		// for a config that had none, it's the grid fallback. Semantics must
		// be untouched besides that.
		JsonNode semantic = stripDiagram(exported);
		expect(original.equals(semantic),
				"round trip must preserve every field", () -> diff(original, semantic));
	}

	/// Removes the export-generated diagram section for semantic comparison.
	static JsonNode stripDiagram(JsonNode exported) {
		com.fasterxml.jackson.databind.node.ObjectNode copy =
				(com.fasterxml.jackson.databind.node.ObjectNode) exported.deepCopy();
		copy.remove("diagram");
		return copy;
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
		JsonNode exported = stripDiagram(new FsmarExportServlet().buildFsmarJson(xml));
		expect(original.equals(exported),
				"tier dispatch must round-trip exactly", () -> diff(original, exported));

		// And it must validate clean: mutually exclusive conditions with the
		// default LAST produce no shadowing findings.
		List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(), infos = new ArrayList<>();
		new FsmarValidateServlet().validate(original, errors, warnings, infos);
		expect(errors.isEmpty() && warnings.isEmpty(),
				"dispatch family must validate clean, got errors=" + errors + " warnings=" + warnings, null);
	}

	static void diagramPlacementsSurviveRoundTrip() throws Exception {
		// Default ingress (null) + a placed state, no named ingresses. Stored
		// positions must override the grid and survive the full-equality check
		// WITH the diagram section.
		String cfg = "{\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"IN-1\",\"next\":\"screening\"}]}}},"
				+ "\"screening\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"OUT-1\",\"next\":\"null\"}]}}}},"
				+ "\"diagram\":{"
				+ "\"states\":{\"null\":{\"x\":10,\"y\":20},\"screening\":{\"x\":300,\"y\":44}}}}";
		JsonNode original = mapper.readTree(cfg);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);

		expect(original.equals(exported),
				"diagram placements must round-trip exactly", () -> diff(original, exported));
		// And the diagram must never leak into the FlowModel extra blob.
		expect(!xml.contains("\"diagram\""),
				"diagram must be geometry, not an extra attribute, in the XML", null);
	}

	static void multiIngressRoundTrip() throws Exception {
		// The multi-SBC picture, honest: two NAMED ingress entry states, each
		// with its OWN selectors, reached by generated source-dispatch
		// transitions on "null"; plus the default ingress (null) routing. The
		// config is authored in the canonical form export produces (dispatch
		// transitions lead null's trigger), so it must round-trip exactly AND
		// each ingress's selectors stay on its own state.
		String cfg = "{\"defaultApplication\":\"b2bua\",\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "{\"id\":\"dispatch-SBC-Dallas\",\"when\":\"${originIP} == '10.1.1.1'\",\"next\":\"SBC-Dallas\"},"
				+ "{\"id\":\"dispatch-SBC-Chicago\",\"when\":\"${originIP} == '10.2.2.2'\",\"next\":\"SBC-Chicago\"},"
				+ "{\"id\":\"def\",\"next\":\"b2bua\"}]}}},"
				+ "\"SBC-Dallas\":{\"selectors\":[{\"type\":\"attribute\",\"id\":\"cust\",\"attribute\":\"X-Dallas-Cust\"}],"
				+ "\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"d1\",\"next\":\"b2bua\"}]}}},"
				+ "\"SBC-Chicago\":{\"selectors\":[{\"type\":\"attribute\",\"id\":\"cust\",\"attribute\":\"X-Chi-Cust\"}],"
				+ "\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"c1\",\"next\":\"b2bua\"}]}}},"
				+ "\"b2bua\":{\"triggers\":{}}},"
				+ "\"diagram\":{"
				+ "\"states\":{\"null\":{\"x\":40,\"y\":40},\"SBC-Dallas\":{\"x\":40,\"y\":160},"
				+ "\"SBC-Chicago\":{\"x\":40,\"y\":280},\"b2bua\":{\"x\":400,\"y\":120}},"
				+ "\"ingresses\":{\"SBC-Dallas\":{\"match\":\"${originIP} == '10.1.1.1'\"},"
				+ "\"SBC-Chicago\":{\"match\":\"${originIP} == '10.2.2.2'\"}}}}";
		JsonNode original = mapper.readTree(cfg);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);

		expect(original.equals(exported),
				"multi-ingress diagram must round-trip exactly", () -> diff(original, exported));
		// Each ingress's selectors round-trip to its OWN state (the asymmetry
		// is gone): Dallas keeps X-Dallas-Cust, Chicago keeps X-Chi-Cust.
		expect("X-Dallas-Cust".equals(exported.path("states").path("SBC-Dallas")
						.path("selectors").path(0).path("attribute").asText()),
				"Dallas ingress must keep its own selector", null);
		expect("X-Chi-Cust".equals(exported.path("states").path("SBC-Chicago")
						.path("selectors").path(0).path("attribute").asText()),
				"Chicago ingress must keep its own selector", null);
	}

	static void dispatchGeneratedAndAbsorbed() throws Exception {
		// A bare config with a named ingress but NO dispatch transition on
		// null: export must GENERATE the dispatch; and the XML must NOT draw
		// the dispatch as an arrow into the ingress (it's absorbed).
		String cfg = "{\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"def\",\"next\":\"b2bua\"}]}}},"
				+ "\"SBC-Dallas\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"d1\",\"next\":\"b2bua\"}]}}},"
				+ "\"b2bua\":{\"triggers\":{}}},"
				+ "\"diagram\":{\"ingresses\":{\"SBC-Dallas\":{\"match\":\"${originIP} == '10.1.1.1'\"}}}}";
		JsonNode original = mapper.readTree(cfg);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);

		// No drawn edge whose target is the ingress (the dispatch is absorbed).
		expect(!xml.contains("target=\"" + ingressCellId(xml, "SBC-Dallas") + "\""),
				"dispatch transition must be absorbed, not drawn as an arrow", null);

		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);
		JsonNode nullTx = exported.path("states").path("null").path("triggers")
				.path("INVITE").path("transitions");
		expect("dispatch-SBC-Dallas".equals(nullTx.path(0).path("id").asText())
						&& "SBC-Dallas".equals(nullTx.path(0).path("next").asText())
						&& "${originIP} == '10.1.1.1'".equals(nullTx.path(0).path("when").asText()),
				"export must regenerate the dispatch transition first on null, got " + nullTx, null);
		expect("def".equals(nullTx.path(1).path("id").asText()),
				"null's own routing must follow the dispatch, got " + nullTx, null);
	}

	/// Finds the cell id of the ingress box with the given label in the XML.
	static String ingressCellId(String xml, String label) throws Exception {
		javax.xml.parsers.DocumentBuilder db =
				javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
		org.w3c.dom.Document doc = db.parse(new java.io.ByteArrayInputStream(
				xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		org.w3c.dom.NodeList gws = doc.getElementsByTagName("Gateway");
		for (int i = 0; i < gws.getLength(); i++) {
			org.w3c.dom.Element g = (org.w3c.dom.Element) gws.item(i);
			if (label.equals(g.getAttribute("label"))) return g.getAttribute("id");
		}
		return "_none_";
	}

	static void cellIdsLiveOnWrappers() throws Exception {
		// mxCellCodec.beforeDecode reads the cell id from the WRAPPER element;
		// an id on the inner mxCell is invisible to the codec, idrefs
		// (parent/source/target) resolve against detached duplicates, and the
		// editor renders an empty canvas with unconnected edges. Field bug
		// 2026-06-12. The export servlet tolerates both shapes, so only this
		// structural check guards the browser decode.
		String cfg = "{\"states\":{"
				+ "\"null\":{\"triggers\":{\"SUBSCRIBE\":{\"transitions\":[{\"next\":\"presence\"}]}}},"
				+ "\"presence\":{\"triggers\":{\"SUBSCRIBE\":{\"transitions\":[{\"next\":\"null\"}]}}}}}";
		String xml = new FsmarImportServlet().buildMxGraphXml(mapper.readTree(cfg));

		javax.xml.parsers.DocumentBuilder db =
				javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
		org.w3c.dom.Document doc = db.parse(new java.io.ByteArrayInputStream(
				xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		for (String tag : new String[] { "Layer", "Gateway", "State", "Transition" }) {
			org.w3c.dom.NodeList wrappers = doc.getElementsByTagName(tag);
			expect(wrappers.getLength() > 0, "test config must produce a " + tag + " element", null);
			for (int i = 0; i < wrappers.getLength(); i++) {
				org.w3c.dom.Element wrapper = (org.w3c.dom.Element) wrappers.item(i);
				expect(wrapper.hasAttribute("id"),
						tag + "[" + i + "] wrapper must carry the cell id", null);
				org.w3c.dom.NodeList cells = wrapper.getElementsByTagName("mxCell");
				for (int c = 0; c < cells.getLength(); c++) {
					expect(!((org.w3c.dom.Element) cells.item(c)).hasAttribute("id"),
							tag + "[" + i + "] inner mxCell must NOT carry an id "
							+ "(invisible to mxCellCodec.beforeDecode)", null);
				}
			}
		}
	}

	static void egressRoundTrip() throws Exception {
		// A terminal transition (no `next`, carries routes) is an egress — the
		// call leaves OCCAS. diagram.egresses names the exit; the transition's
		// routes/modifier match it. Both must survive import → export: the
		// transition stays terminal with its routes baked back on, and the
		// egress metadata reappears.
		String cfg = "{\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"INV-1\",\"next\":\"b2bua\"}]}}},"
				+ "\"b2bua\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"B2B-offnet\",\"when\":\"${To.user} matches '1[2-9]\\\\d{9}'\","
				+ "   \"subscriber\":\"To\",\"routes\":[\"sip:${To.user}@carrier-trunk\"],"
				+ "   \"routeModifier\":\"ROUTE_FINAL\"}]}}}},"
				+ "\"diagram\":{\"egresses\":{\"to-carrier\":{\"description\":\"PSTN carrier\","
				+ "  \"routes\":[\"sip:${To.user}@carrier-trunk\"],\"routeModifier\":\"ROUTE_FINAL\"}}}}";
		JsonNode original = mapper.readTree(cfg);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);

		JsonNode tx = exported.path("states").path("b2bua").path("triggers")
				.path("INVITE").path("transitions").path(0);
		expect(!tx.has("next") || tx.path("next").isNull(),
				"egress transition must stay terminal (no next), got " + tx, null);
		expect("B2B-offnet".equals(tx.path("id").asText()),
				"egress transition id must survive, got " + tx, null);
		expect("sip:${To.user}@carrier-trunk".equals(tx.path("routes").path(0).asText()),
				"egress transition must carry the baked route, got " + tx, null);
		expect("ROUTE_FINAL".equals(tx.path("routeModifier").asText()),
				"egress transition must carry ROUTE_FINAL, got " + tx, null);
		expect("To".equals(tx.path("subscriber").asText()),
				"egress transition must keep its subscriber, got " + tx, null);

		JsonNode eg = exported.path("diagram").path("egresses").path("to-carrier");
		expect(eg.isObject(), "diagram.egresses must round-trip the named exit, got "
				+ exported.path("diagram"), null);
		// ROUTE_FINAL is implied by the ABSENCE of a returnState (no out-edge);
		// the egress metadata carries only routes + description.
		expect(!eg.has("returnState")
				&& "sip:${To.user}@carrier-trunk".equals(eg.path("routes").path(0).asText())
				&& "PSTN carrier".equals(eg.path("description").asText()),
				"egress metadata (routes/description, no returnState) must round-trip, got " + eg, null);
	}

	static void routeBackEgressRoundTrip() throws Exception {
		// A ROUTE_BACK egress: a transition routes out (routes) and resumes at
		// `next` (b2bua) on return. The editor draws it as an egress with a line
		// back to b2bua; diagram.egresses carries the returnState. After import →
		// export the transition keeps next=b2bua + ROUTE_BACK + routes, and the
		// egress's returnState round-trips.
		String cfg = "{\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"T0\",\"next\":\"screening\"}]}}},"
				+ "\"screening\":{\"triggers\":{\"INVITE\":{\"transitions\":["
				+ "  {\"id\":\"SCR-anon\",\"next\":\"b2bua\",\"subscriber\":\"To\","
				+ "   \"routeModifier\":\"ROUTE_BACK\",\"routes\":[\"sip:greeting@media\"]}]}}},"
				+ "\"b2bua\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"B2B\",\"next\":\"registrar\"}]}}}"
				+ "},"
				+ "\"diagram\":{\"egresses\":{\"media-greeting\":{\"description\":\"greeting\","
				+ "  \"routes\":[\"sip:greeting@media\"],\"returnState\":\"b2bua\"}}}}";
		JsonNode original = mapper.readTree(cfg);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);

		JsonNode tx = exported.path("states").path("screening").path("triggers")
				.path("INVITE").path("transitions").path(0);
		expect("b2bua".equals(tx.path("next").asText()),
				"route-back transition must resume at b2bua (next), got " + tx, null);
		expect("ROUTE_BACK".equals(tx.path("routeModifier").asText()),
				"route-back transition must keep ROUTE_BACK, got " + tx, null);
		expect("sip:greeting@media".equals(tx.path("routes").path(0).asText()),
				"route-back transition must keep its route, got " + tx, null);
		expect("To".equals(tx.path("subscriber").asText()),
				"route-back transition must keep its subscriber, got " + tx, null);

		JsonNode eg = exported.path("diagram").path("egresses").path("media-greeting");
		expect("b2bua".equals(eg.path("returnState").asText()),
				"egress returnState must round-trip, got " + eg, null);
		expect("sip:greeting@media".equals(eg.path("routes").path(0).asText()),
				"egress routes must round-trip, got " + eg, null);
	}

	static void stateIdAndAppRoundTrip() throws Exception {
		// Two states (b2bua-caller, b2bua-callee) invoke the SAME application
		// (b2bua) — distinct ids, same app. The state id (map key) and the `app`
		// field must survive import → export; a state whose app equals its id
		// (screening) must emit NO `app`.
		String cfg = "{\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"T0\",\"next\":\"b2bua-caller\"}]}}},"
				+ "\"b2bua-caller\":{\"app\":\"b2bua\",\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"T1\",\"next\":\"b2bua-callee\"}]}}},"
				+ "\"b2bua-callee\":{\"app\":\"b2bua\",\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"T2\",\"next\":\"screening\"}]}}},"
				+ "\"screening\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"id\":\"T3\",\"next\":\"registrar\"}]}}}"
				+ "}}";
		JsonNode original = mapper.readTree(cfg);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);

		JsonNode states = exported.path("states");
		expect(states.has("b2bua-caller") && states.has("b2bua-callee"),
				"both same-app states must survive by their distinct ids, got " + states.fieldNames(), null);
		expect("b2bua".equals(states.path("b2bua-caller").path("app").asText()),
				"b2bua-caller must keep app=b2bua, got " + states.path("b2bua-caller"), null);
		expect("b2bua".equals(states.path("b2bua-callee").path("app").asText()),
				"b2bua-callee must keep app=b2bua, got " + states.path("b2bua-callee"), null);
		expect("b2bua-callee".equals(states.path("b2bua-caller").path("triggers").path("INVITE")
				.path("transitions").path(0).path("next").asText()),
				"caller leg must route to the callee-leg state id, got "
				+ states.path("b2bua-caller").path("triggers"), null);
		expect(!states.path("screening").has("app"),
				"a state whose app equals its id must emit no 'app', got " + states.path("screening"), null);
	}

	static void bareConfigGetsDiagramOnExport() throws Exception {
		// A config with no diagram section still exports with one — grid
		// fallback positions including the default ingress ("null") — and no
		// ingresses map (none are named) so the next save is layout-stable.
		String cfg = "{\"states\":{"
				+ "\"null\":{\"triggers\":{\"INVITE\":{\"transitions\":[{\"next\":\"b2bua\"}]}}},"
				+ "\"b2bua\":{\"triggers\":{}}}}";
		JsonNode original = mapper.readTree(cfg);
		String xml = new FsmarImportServlet().buildMxGraphXml(original);
		JsonNode exported = new FsmarExportServlet().buildFsmarJson(xml);

		JsonNode diagram = exported.path("diagram");
		JsonNode b2bua = diagram.path("states").path("b2bua");
		expect(b2bua.path("x").isInt() && b2bua.path("y").isInt(),
				"bare config must gain integer state placements on export, got " + diagram, null);
		expect(diagram.path("states").path("null").path("x").isInt(),
				"default ingress (null) must get a placement, got " + diagram, null);
		expect(!diagram.has("ingresses"),
				"bare config has no named ingresses, got " + diagram, null);
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
