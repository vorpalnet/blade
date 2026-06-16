package org.vorpal.blade.applications.console.mxgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Smoke test for the Route Simulator core ([RouteSimulator]) — drives the
/// engine-mirror walk with a config equivalent to the FSMAR 3 sample
/// (tier table, `matches` toll-free rule, screening chain) and verifies the
/// shared trace format hop by hop: full call-path chaining, capture-and-carry
/// into later hops' routes, undeployed bypass, cycle detection, default
/// fallback, and pseudo-variable overrides.
///
/// Run via `main`, like the other smoke tests.
public final class SimulateSmokeTest {
	private static int passed;
	private static int failed;

	private static final ObjectMapper mapper = new ObjectMapper();

	/// The fsmar3 sample's shape, minus about/logging. Backticks become
	/// double quotes (single quotes appear inside `when` expressions).
	private static final String CONFIG = q("{"
			+ " `defaultApplication`: `b2bua`,"
			+ " `states`: {"
			+ "  `null`: {"
			+ "   `selectors`: ["
			+ "    {`type`:`regex`,`id`:`From`,`attribute`:`From`,`pattern`:`.*<sips?:(?<user>[^@]+)@(?<host>[^>;]+).*`},"
			+ "    {`type`:`regex`,`id`:`To`,`attribute`:`To`,`pattern`:`.*<sips?:(?<user>[^@]+)@(?<host>[^>;]+).*`},"
			+ "    {`type`:`attribute`,`id`:`callerIp`,`attribute`:`originIP`},"
			+ "    {`type`:`table`,`id`:`customerTier`,`table`:{"
			+ "      `keyExpression`:`${From.user}`,"
			+ "      `translations`:{`alice`:{`tier`:`gold`},`bob`:{`tier`:`silver`}}}}"
			+ "   ],"
			+ "   `triggers`: {"
			+ "    `INVITE`: { `transitions`: ["
			+ "     {`id`:`INV-bob`,`when`:`${To.user} == 'bob' && ${From.user} == 'alice'`,"
			+ "      `next`:`b2bua`,`subscriber`:`From`,`routes`:[`sip:${To.user}@special-proxy`]},"
			+ "     {`id`:`INV-gold`,`when`:`${tier} == 'gold'`,"
			+ "      `next`:`b2bua`,`subscriber`:`From`,`routes`:[`sip:${From.user}@gold-trunk`]},"
			+ "     {`id`:`INV-tollfree`,`when`:`${To.user} matches '18(00|88|77|66)\\\\d{7}'`,"
			+ "      `next`:`b2bua`,`subscriber`:`To`},"
			+ "     {`id`:`INV-default`,`next`:`screening`,`subscriber`:`From`}"
			+ "    ]}"
			+ "   }"
			+ "  },"
			+ "  `screening`: {"
			+ "   `selectors`: ["
			+ "    {`type`:`regex`,`id`:`callerNow`,`attribute`:`From`,`pattern`:`.*<sips?:(?<user>[^@]+)@(?<host>[^>;]+).*`}"
			+ "   ],"
			+ "   `triggers`: {"
			+ "    `INVITE`: { `transitions`: ["
			+ "     {`id`:`SCR-anon`,`when`:`${callerNow.user} == 'anonymous'`,`next`:`b2bua`,"
			+ "      `subscriber`:`To`,`routes`:[`sip:${To.user}@anon-gw`]},"
			+ "     {`id`:`SCR-normal`,`next`:`b2bua`,`subscriber`:`To`}"
			+ "    ]}"
			+ "   }"
			+ "  },"
			+ "  `b2bua`: {"
			+ "   `triggers`: {"
			+ "    `INVITE`: { `transitions`: ["
			+ "     {`id`:`B2B-deliver`,`next`:`proxy-registrar`,`subscriber`:`To`,"
			+ "      `routes`:[`sip:${To.user}@registrar`]}"
			+ "    ]}"
			+ "   }"
			+ "  }"
			+ " }"
			+ "}");

	public static void main(String[] args) throws Exception {
		testFullCallPath();
		testGoldTierAndSubscriber();
		testTollfreeMatches();
		testScreeningChainAndRecapture();
		testUndeployedBypass();
		testCycleDetection();
		testDefaultFallback();
		testPseudoOverrides();
		testBadSelectorReported();
		testExtractUri();
		testIngressDispatch();
		testSubnetIngressDispatch();
		testEgressExit();
		testRouteBackEgress();
		testTwiceInvokedApp();
		summary();
	}

	/// The sample's shape: ingresses classified by source IP SUBNET via the
	/// `insubnet` CIDR operator. 10.20.x → Atlanta, 10.30.x → Dallas, anything
	/// else → the default ingress. Proves insubnet + null-dispatch + bypass
	/// end to end through the engine mirror.
	/// A terminal transition (no `next`) carrying routes is an egress: the call
	/// leaves OCCAS. The simulator must flag the hop and the outcome, resolve
	/// the routes, and — for an egress at the entry state — NOT clobber it with
	/// the default-application fallback (mirrors AppRouter's routedExternally).
	/// The same application invoked TWICE on one path — two states with distinct
	/// ids ("b2bua-caller", "b2bua-callee") but the same `app` ("b2bua"). The
	/// simulator must visit each as its own hop (resuming by state id, not app
	/// name) and resolve the application via each state.
	private static void testTwiceInvokedApp() throws Exception {
		String cfg = q("{`states`:{"
				+ "`null`:{`triggers`:{`INVITE`:{`transitions`:[{`id`:`T0`,`next`:`b2bua-caller`}]}}},"
				+ "`b2bua-caller`:{`app`:`b2bua`,`triggers`:{`INVITE`:{`transitions`:["
				+ "  {`id`:`T1`,`next`:`b2bua-callee`,`subscriber`:`From`}]}}},"
				+ "`b2bua-callee`:{`app`:`b2bua`,`triggers`:{`INVITE`:{`transitions`:["
				+ "  {`id`:`T2`,`next`:`registrar`,`subscriber`:`To`}]}}}"
				+ "}}");
		ObjectNode simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(cfg));
		ObjectNode req = simReq.putObject("request");
		req.put("method", "INVITE");
		ObjectNode h = req.putObject("headers");
		h.put("To", "<sip:bob@example.com>");
		h.put("From", "<sip:alice@example.com>;tag=1");
		JsonNode t = RouteSimulator.simulate(simReq, mapper);

		JsonNode hops = t.path("hops");
		check("twice: four hops (null, caller, callee, registrar)", hops.size() == 4);
		check("twice: hop2 is the caller-leg state", "b2bua-caller".equals(hops.path(1).path("state").asText()));
		check("twice: hop2 runs app b2bua", "b2bua".equals(hops.path(1).path("app").asText()));
		check("twice: hop3 is the callee-leg state", "b2bua-callee".equals(hops.path(2).path("state").asText()));
		check("twice: hop3 runs app b2bua", "b2bua".equals(hops.path(2).path("app").asText()));
		check("twice: the two legs are distinct states",
				!hops.path(1).path("state").asText().equals(hops.path(2).path("state").asText()));
		check("twice: final application is registrar", "registrar".equals(t.path("finalApp").asText()));
	}

	/// A ROUTE_BACK egress: the call routes OUT to an external server, then the
	/// flow RESUMES at the egress's return state. The simulator must flag the
	/// egress hop (not terminal) and continue the walk at the return state.
	private static void testRouteBackEgress() throws Exception {
		String cfg = q("{`defaultApplication`:`b2bua`,`states`:{"
				+ "`null`:{`triggers`:{`INVITE`:{`transitions`:[{`id`:`T0`,`next`:`screening`}]}}},"
				+ "`screening`:{`triggers`:{`INVITE`:{`transitions`:["
				+ "  {`id`:`SCR-anon`,`next`:`b2bua`,`routeModifier`:`ROUTE_BACK`,"
				+ "   `routes`:[`sip:greeting@media.example.com`]}]}}},"
				+ "`b2bua`:{`triggers`:{`INVITE`:{`transitions`:[{`id`:`B2B`,`next`:`registrar`}]}}}"
				+ "}}");
		ObjectNode simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(cfg));
		ObjectNode req = simReq.putObject("request");
		req.put("method", "INVITE");
		ObjectNode h = req.putObject("headers");
		h.put("To", "<sip:bob@example.com>");
		JsonNode t = RouteSimulator.simulate(simReq, mapper);

		JsonNode hops = t.path("hops");
		// null -> screening -> (route-back) b2bua -> registrar(terminal)
		check("route-back: four hops", hops.size() == 4);
		JsonNode scr = hops.path(1);
		check("route-back: screening hop is an egress", scr.path("egress").asBoolean());
		check("route-back: screening hop is ROUTE_BACK", "ROUTE_BACK".equals(scr.path("routeModifier").asText()));
		check("route-back: returnsTo b2bua", "b2bua".equals(scr.path("returnsTo").asText()));
		check("route-back: route resolved", "sip:greeting@media.example.com".equals(scr.path("routes").path(0).asText()));
		check("route-back: flow resumes at b2bua", "b2bua".equals(hops.path(2).path("state").asText()));
		// The call did NOT leave for good — it continued and ended at registrar.
		check("route-back: not a final egress", !t.path("egress").asBoolean());
		check("route-back: final application is registrar", "registrar".equals(t.path("finalApp").asText()));
	}

	private static void testEgressExit() throws Exception {
		String cfg = q("{`defaultApplication`:`b2bua`,`states`:{"
				+ "`null`:{`selectors`:[{`type`:`regex`,`id`:`To`,`attribute`:`To`,"
				+ "  `pattern`:`.*<sips?:(?<user>[^@]+)@(?<host>[^>;]+).*`}],"
				+ " `triggers`:{`INVITE`:{`transitions`:["
				+ "  {`id`:`OFFNET`,`subscriber`:`To`,`routes`:[`sip:${To.user}@carrier-trunk`],"
				+ "   `routeModifier`:`ROUTE_FINAL`}"
				+ " ]}}}}}");
		ObjectNode simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(cfg));
		ObjectNode req = simReq.putObject("request");
		req.put("method", "INVITE");
		req.put("requestURI", "sip:12125551212@example.com");
		ObjectNode h = req.putObject("headers");
		h.put("To", "<sip:12125551212@example.com>");
		h.put("From", "<sip:alice@example.com>;tag=1");
		JsonNode t = RouteSimulator.simulate(simReq, mapper);

		check("egress: outcome flagged egress", t.path("egress").asBoolean());
		check("egress: no finalApp (call left OCCAS)", !t.has("finalApp"));
		check("egress: default fallback NOT triggered", !t.path("defaultFallback").asBoolean());
		JsonNode hop = t.path("hops").get(0);
		check("egress: hop flagged egress", hop.path("egress").asBoolean());
		check("egress: hop route resolved from context",
				"sip:12125551212@carrier-trunk".equals(hop.path("routes").get(0).asText()));
		check("egress: hop ROUTE_FINAL", "ROUTE_FINAL".equals(hop.path("routeModifier").asText()));
	}

	private static void testSubnetIngressDispatch() throws Exception {
		String cfg = q("{`states`:{"
				+ "`null`:{"
				+ "  `selectors`:[{`type`:`attribute`,`id`:`originIP`,`attribute`:`originIP`}],"
				+ "  `triggers`:{`INVITE`:{`transitions`:["
				+ "    {`id`:`dispatch-Atlanta`,`when`:`${originIP} insubnet '10.20.0.0/16'`,`next`:`Atlanta`},"
				+ "    {`id`:`dispatch-Dallas`,`when`:`${originIP} insubnet '10.30.0.0/16'`,`next`:`Dallas`},"
				+ "    {`id`:`def`,`next`:`b2bua`}"
				+ "  ]}}},"
				+ "`Atlanta`:{`triggers`:{`INVITE`:{`transitions`:[{`id`:`ATL-in`,`next`:`b2bua`}]}}},"
				+ "`Dallas`:{`triggers`:{`INVITE`:{`transitions`:[{`id`:`DAL-in`,`next`:`b2bua`}]}}},"
				+ "`b2bua`:{`triggers`:{}}"
				+ "}}");

		check("subnet: Atlanta IP enters Atlanta",
				"Atlanta".equals(subnetSim(cfg, "10.20.5.9").path("hops").get(1).path("state").asText()));
		check("subnet: Atlanta dispatch matched",
				"dispatch-Atlanta".equals(subnetSim(cfg, "10.20.5.9").path("hops").get(0).path("matched").asText()));
		check("subnet: Dallas IP enters Dallas",
				"Dallas".equals(subnetSim(cfg, "10.30.5.9").path("hops").get(1).path("state").asText()));
		JsonNode other = subnetSim(cfg, "192.0.2.1");
		check("subnet: unmatched IP falls to default ingress",
				"def".equals(other.path("hops").get(0).path("matched").asText())
						&& other.path("hops").size() == 2);
	}

	private static JsonNode subnetSim(String cfg, String originIP) throws Exception {
		ObjectNode simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(cfg));
		ObjectNode req = simReq.putObject("request");
		req.put("method", "INVITE");
		req.put("requestURI", "sip:x@example.com");
		ObjectNode h = req.putObject("headers");
		h.put("From", "<sip:a@example.com>;tag=1");
		h.put("To", "<sip:x@example.com>");
		h.put("Call-ID", "subnet-" + originIP + "@test");
		h.put("originIP", originIP);
		return RouteSimulator.simulate(simReq, mapper);
	}

	/// Multi-ingress entry: a named ingress (SBC-Dallas) is reached from the
	/// "null" dispatch layer by a source match, then runs its OWN selectors
	/// and routes onward — the bypass loop walking null → SBC-Dallas → app.
	/// Unmatched source falls through to the default ingress's routing.
	private static void testIngressDispatch() throws Exception {
		String cfg = q("{`states`:{"
				+ "`null`:{"
				+ "  `selectors`:[{`type`:`attribute`,`id`:`src`,`attribute`:`X-Src`}],"
				+ "  `triggers`:{`INVITE`:{`transitions`:["
				+ "    {`id`:`dispatch-SBC-Dallas`,`when`:`${src} == 'dallas'`,`next`:`SBC-Dallas`},"
				+ "    {`id`:`def`,`next`:`b2bua`}"
				+ "  ]}}},"
				+ "`SBC-Dallas`:{"
				+ "  `selectors`:[{`type`:`attribute`,`id`:`cust`,`attribute`:`X-Dallas-Cust`}],"
				+ "  `triggers`:{`INVITE`:{`transitions`:[{`id`:`d1`,`next`:`b2bua`}]}}},"
				+ "`b2bua`:{`triggers`:{}}"
				+ "}}");

		// Dallas source → classified into SBC-Dallas, which runs its selector.
		ObjectNode simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(cfg));
		ObjectNode req = simReq.putObject("request");
		req.put("method", "INVITE");
		req.put("requestURI", "sip:x@example.com");
		ObjectNode h = req.putObject("headers");
		h.put("From", "<sip:a@example.com>;tag=1");
		h.put("To", "<sip:x@example.com>");
		h.put("Call-ID", "ingress-dallas@test");
		h.put("X-Src", "dallas");
		h.put("X-Dallas-Cust", "ACME");
		JsonNode t = RouteSimulator.simulate(simReq, mapper);

		check("ingress: hop1 is null", "null".equals(t.path("hops").get(0).path("state").asText()));
		check("ingress: hop1 dispatch-SBC-Dallas matched",
				"dispatch-SBC-Dallas".equals(t.path("hops").get(0).path("matched").asText()));
		JsonNode h2 = t.path("hops").get(1);
		check("ingress: hop2 continues from SBC-Dallas state",
				"SBC-Dallas".equals(h2.path("state").asText()));
		check("ingress: SBC-Dallas runs its OWN selector",
				"ACME".equals(h2.path("extracted").path("cust").asText()));
		check("ingress: call routes onward to b2bua", "b2bua".equals(t.path("finalApp").asText()));

		// Unmatched source → default ingress routing (no Dallas hop).
		simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(cfg));
		req = simReq.putObject("request");
		req.put("method", "INVITE");
		req.put("requestURI", "sip:x@example.com");
		h = req.putObject("headers");
		h.put("From", "<sip:a@example.com>;tag=1");
		h.put("To", "<sip:x@example.com>");
		h.put("Call-ID", "ingress-other@test");
		h.put("X-Src", "elsewhere");
		JsonNode t2 = RouteSimulator.simulate(simReq, mapper);
		check("ingress: unmatched source falls to default",
				"def".equals(t2.path("hops").get(0).path("matched").asText()));
		check("ingress: default routes straight to b2bua (no Dallas hop)",
				"b2bua".equals(t2.path("finalApp").asText())
						&& t2.path("hops").size() == 2);
	}

	private static JsonNode simulate(String from, String to, String method,
			String[] undeployed, ObjectNode pseudo) throws Exception {
		ObjectNode simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(CONFIG));
		ObjectNode req = simReq.putObject("request");
		req.put("method", method);
		req.put("requestURI", "sip:" + to + "@example.com");
		ObjectNode headers = req.putObject("headers");
		headers.put("From", "<sip:" + from + "@example.com>;tag=1");
		headers.put("To", "<sip:" + to + "@example.com>");
		headers.put("Call-ID", "smoke-" + from + "-" + to + "@test");
		if (undeployed != null) {
			ArrayNode u = simReq.putArray("undeployed");
			for (String s : undeployed) u.add(s);
		}
		if (pseudo != null) {
			simReq.set("pseudo", pseudo);
		}
		return RouteSimulator.simulate(simReq, mapper);
	}

	/// alice→bob: INV-bob fires at ingress, then the walk chains through the
	/// whole call path the way the container invokes the router per hop.
	private static void testFullCallPath() throws Exception {
		JsonNode t = simulate("alice", "bob", "INVITE", null, null);

		check("path: three hops (null, b2bua, proxy-registrar)", t.path("hops").size() == 3);
		JsonNode h1 = t.path("hops").get(0);
		check("path: hop1 state null", "null".equals(h1.path("state").asText()));
		check("path: hop1 INV-bob matched", "INV-bob".equals(h1.path("matched").asText()));
		check("path: hop1 first-match-wins (one evaluation)", h1.path("evaluated").size() == 1);
		check("path: hop1 route resolved", "sip:bob@special-proxy".equals(h1.path("routes").get(0).asText()));
		check("path: hop1 pseudo-vars in extracted", "INVITE".equals(h1.path("extracted").path("method").asText()));
		check("path: hop1 selector values in extracted", "alice".equals(h1.path("extracted").path("From.user").asText()));
		check("path: hop1 tier classified", "gold".equals(h1.path("extracted").path("tier").asText()));

		JsonNode h2 = t.path("hops").get(1);
		check("path: hop2 state b2bua", "b2bua".equals(h2.path("state").asText()));
		check("path: hop2 carries To.user into route", "sip:bob@registrar".equals(h2.path("routes").get(0).asText()));
		check("path: hop2 previousApp refreshed", "b2bua".equals(h2.path("extracted").path("previousApp").asText()));

		JsonNode h3 = t.path("hops").get(2);
		check("path: hop3 terminal (no transitions)", h3.path("evaluated").size() == 0 && !h3.has("matched"));
		check("path: finalApp is last routed app", "proxy-registrar".equals(t.path("finalApp").asText()));
		check("path: no fallback/cycle", !t.path("defaultFallback").asBoolean() && !t.path("cycleDetected").asBoolean());
	}

	/// alice→carol: INV-bob misses, the tier table classifies alice gold,
	/// INV-gold fires. Subscriber URI comes from the From header.
	private static void testGoldTierAndSubscriber() throws Exception {
		JsonNode t = simulate("alice", "carol", "INVITE", null, null);
		JsonNode h1 = t.path("hops").get(0);
		check("gold: INV-bob evaluated false first",
				"INV-bob".equals(h1.path("evaluated").get(0).path("id").asText())
						&& !h1.path("evaluated").get(0).path("fired").asBoolean());
		check("gold: INV-gold fired second", "INV-gold".equals(h1.path("matched").asText()));
		check("gold: when recorded", "${tier} == 'gold'".equals(h1.path("evaluated").get(1).path("when").asText()));
		check("gold: route from carried caller", "sip:alice@gold-trunk".equals(h1.path("routes").get(0).asText()));
		check("gold: subscriberURI extracted from From",
				"sip:alice@example.com".equals(h1.path("subscriberURI").asText()));
		check("gold: region defaults NEUTRAL", "NEUTRAL".equals(h1.path("region").asText()));
		check("gold: routeModifier defaults ROUTE", "ROUTE".equals(h1.path("routeModifier").asText()));
	}

	/// carol→18005551212: the `matches` full-string regex fires.
	private static void testTollfreeMatches() throws Exception {
		JsonNode t = simulate("carol", "18005551212", "INVITE", null, null);
		check("tollfree: INV-tollfree matched",
				"INV-tollfree".equals(t.path("hops").get(0).path("matched").asText()));

		JsonNode t2 = simulate("carol", "14085551212", "INVITE", null, null);
		check("tollfree: ordinary number falls to default",
				"INV-default".equals(t2.path("hops").get(0).path("matched").asText()));
	}

	/// carol→dave: default path through screening; the screening state's
	/// selector re-captures the caller under a new name.
	private static void testScreeningChainAndRecapture() throws Exception {
		JsonNode t = simulate("carol", "dave", "INVITE", null, null);
		check("screen: four hops", t.path("hops").size() == 4);
		JsonNode h2 = t.path("hops").get(1);
		check("screen: hop2 is screening", "screening".equals(h2.path("state").asText()));
		check("screen: callerNow re-captured", "carol".equals(h2.path("extracted").path("callerNow.user").asText()));
		check("screen: SCR-anon missed, SCR-normal fired", "SCR-normal".equals(h2.path("matched").asText()));
		check("screen: finalApp proxy-registrar", "proxy-registrar".equals(t.path("finalApp").asText()));
	}

	/// Mark screening undeployed: the engine bypasses it and continues from
	/// its state as though it had already run.
	private static void testUndeployedBypass() throws Exception {
		JsonNode t = simulate("carol", "dave", "INVITE", new String[] { "screening" }, null);
		JsonNode h1 = t.path("hops").get(0);
		check("bypass: hop1 matched INV-default", "INV-default".equals(h1.path("matched").asText()));
		check("bypass: hop1 flagged bypassed", h1.path("bypassed").asBoolean());
		JsonNode h2 = t.path("hops").get(1);
		check("bypass: hop2 continues from screening state", "screening".equals(h2.path("state").asText()));
		check("bypass: hop2 selectors still run", "carol".equals(h2.path("extracted").path("callerNow.user").asText()));
		check("bypass: call still reaches registrar", "proxy-registrar".equals(t.path("finalApp").asText()));
	}

	/// Two undeployed apps pointing at each other: the per-invocation visited
	/// set detects the loop.
	private static void testCycleDetection() throws Exception {
		String cyclic = q("{`states`:{"
				+ "`null`:{`triggers`:{`INVITE`:{`transitions`:[{`id`:`T1`,`next`:`appA`}]}}},"
				+ "`appA`:{`triggers`:{`INVITE`:{`transitions`:[{`id`:`T2`,`next`:`appB`}]}}},"
				+ "`appB`:{`triggers`:{`INVITE`:{`transitions`:[{`id`:`T3`,`next`:`appA`}]}}}"
				+ "}}");
		ObjectNode simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(cyclic));
		simReq.putObject("request").put("method", "INVITE").putObject("headers");
		simReq.putArray("undeployed").add("appA").add("appB");
		JsonNode t = RouteSimulator.simulate(simReq, mapper);

		check("cycle: detected", t.path("cycleDetected").asBoolean());
		check("cycle: three hops then stop", t.path("hops").size() == 3);
		check("cycle: no finalApp", !t.has("finalApp"));
		check("cycle: no fallback after advancing", !t.path("defaultFallback").asBoolean());
	}

	/// A method with no trigger at ingress: the defaultApplication fallback
	/// fires, and the walk continues from the default app's state.
	private static void testDefaultFallback() throws Exception {
		JsonNode t = simulate("carol", "dave", "MESSAGE", null, null);
		check("fallback: flagged", t.path("defaultFallback").asBoolean());
		check("fallback: finalApp is default", "b2bua".equals(t.path("finalApp").asText()));
		check("fallback: continues from default state", t.path("hops").size() == 2
				&& "b2bua".equals(t.path("hops").get(1).path("state").asText()));

		// Default marked undeployed: the engine would route to null.
		JsonNode t2 = simulate("carol", "dave", "MESSAGE", new String[] { "b2bua" }, null);
		check("fallback: undeployed default noted", t2.path("defaultFallback").asBoolean()
				&& !t2.has("finalApp") && t2.path("problems").size() > 0);
	}

	/// `${hash100}` override steers the canary condition — "simulate the 4%
	/// bucket" is a form field, not a wait for the right Call-ID.
	private static void testPseudoOverrides() throws Exception {
		String canary = q("{`defaultApplication`:`main`,`states`:{"
				+ "`null`:{`triggers`:{`INVITE`:{`transitions`:["
				+ "{`id`:`T-canary`,`when`:`${hash100} < 5`,`next`:`canary`},"
				+ "{`id`:`T-main`,`next`:`main`}]}}}}}");

		ObjectNode in4 = mapper.createObjectNode();
		in4.put("hash100", "4");
		ObjectNode simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(canary));
		simReq.putObject("request").put("method", "INVITE").putObject("headers");
		simReq.set("pseudo", in4);
		JsonNode t = RouteSimulator.simulate(simReq, mapper);
		check("pseudo: hash100=4 takes canary", "canary".equals(t.path("finalApp").asText()));

		ObjectNode in50 = mapper.createObjectNode();
		in50.put("hash100", "50");
		simReq.set("pseudo", in50);
		JsonNode t2 = RouteSimulator.simulate(simReq, mapper);
		check("pseudo: hash100=50 takes main", "main".equals(t2.path("finalApp").asText()));
	}

	/// A selector that won't deserialize is reported and skipped — the
	/// simulation still completes. (An unknown `type` is NOT that case: the
	/// framework's `defaultImpl = AttributeSelector` swallows it on the
	/// engine too — the validate servlet is what flags it.)
	private static void testBadSelectorReported() throws Exception {
		String unknownType = q("{`defaultApplication`:`main`,`states`:{"
				+ "`null`:{`selectors`:[{`type`:`bogus`,`id`:`x`}],"
				+ "`triggers`:{`INVITE`:{`transitions`:[{`id`:`T`,`next`:`main`}]}}}}}");
		ObjectNode simReq = mapper.createObjectNode();
		simReq.set("config", mapper.readTree(unknownType));
		simReq.putObject("request").put("method", "INVITE").putObject("headers");
		JsonNode t = RouteSimulator.simulate(simReq, mapper);
		check("badsel: unknown type tolerated like the engine", "main".equals(t.path("finalApp").asText())
				&& !t.has("problems"));

		// A table selector whose table is structurally wrong does fail
		// deserialization — reported and skipped, simulation completes.
		String broken = q("{`defaultApplication`:`main`,`states`:{"
				+ "`null`:{`selectors`:[{`type`:`table`,`id`:`x`,`table`:`notanobject`}],"
				+ "`triggers`:{`INVITE`:{`transitions`:[{`id`:`T`,`next`:`main`}]}}}}}");
		simReq.set("config", mapper.readTree(broken));
		JsonNode t2 = RouteSimulator.simulate(simReq, mapper);
		check("badsel: simulation completes", "main".equals(t2.path("finalApp").asText()));
		check("badsel: problem reported", t2.path("problems").size() == 1
				&& t2.path("problems").get(0).asText().contains("selectors[0]"));
	}

	private static void testExtractUri() {
		check("uri: angle brackets", "sip:a@b".equals(RouteSimulator.extractUri("\"A\" <sip:a@b>;tag=1")));
		check("uri: bare with params", "sip:a@b".equals(RouteSimulator.extractUri("sip:a@b;tag=1")));
		check("uri: bare", "sip:a@b".equals(RouteSimulator.extractUri(" sip:a@b ")));
		check("uri: null/empty", RouteSimulator.extractUri(null) == null && RouteSimulator.extractUri("") == null);
	}

	private static String q(String s) {
		return s.replace('`', '"');
	}

	private static void check(String name, boolean ok) {
		if (ok) { passed++; System.out.println("  PASS  " + name); }
		else { failed++; System.out.println("  FAIL  " + name); }
	}

	private static void summary() {
		System.out.println("SimulateSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}
}
