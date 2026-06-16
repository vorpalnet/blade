package org.vorpal.blade.library.fsmar3;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.sip.ar.SipApplicationRouterInfo;
import javax.servlet.sip.ar.SipRouteModifier;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;

/// Smoke test for FSMAR's data-driven routing core — exercised directly
/// (without the OCCAS container) by running a state's selectors into a
/// MemoryContext, evaluating transition `when` conditions, and resolving
/// `${}` route templates. Mirrors what AppRouter.getNextApplication does.
///
/// Run via `main`, like the other v3 smoke tests.
public final class FsmarRoutingSmokeTest {
	private static int passed;
	private static int failed;

	private static final String SIP_USER = ".*<sips?:(?<user>[^@]+)@(?<host>[^>;]+).*";

	public static void main(String[] args) {
		testExtractThenMatch();
		testRouteTemplateConstruction();
		testCaptureAndCarryAcrossStates();
		testUnconditionalTransition();
		testFirstMatchWins();
		testSampleConfigShape();
		testPseudoVariables();
		testTierTableClassification();
		testMatchesOperatorInWhen();
		testRegionSerialization();
		testEgressRouterInfo();
		testSampleEgress();
		testTwiceInvokedApp();
		testMetricsCounters();
		summary();
	}

	/// Build a To/From payload the way Selector.readSource reads a Map.
	private static Map<String, Object> invite(String from, String to) {
		Map<String, Object> p = new HashMap<>();
		p.put("From", "<sip:" + from + "@example.com>;tag=1");
		p.put("To", "<sip:" + to + "@example.com>");
		return p;
	}

	private static void testExtractThenMatch() {
		State s = new State();
		s.addSelector(new RegexSelector("From", "From", SIP_USER, null));
		s.addSelector(new RegexSelector("To", "To", SIP_USER, null));
		Transition bob = new Transition().setId("bob")
				.setWhen("${To.user} == 'bob' && ${From.user} == 'alice'").setNext("b2bua");

		Context ctx = new MemoryContext();
		s.extract(ctx, invite("alice", "bob"));
		check("extract+match: alice->bob fires", bob.matches(ctx));

		Context ctx2 = new MemoryContext();
		s.extract(ctx2, invite("carol", "bob"));
		check("extract+match: carol->bob does not fire", !bob.matches(ctx2));
	}

	private static void testRouteTemplateConstruction() {
		State s = new State();
		s.addSelector(new RegexSelector("To", "To", SIP_USER, null));
		Transition t = new Transition().setId("r").setNext("b2bua")
				.setRoutes(new String[] { "sip:${To.user}@special-proxy", "sip:${To.host}" });

		Context ctx = new MemoryContext();
		s.extract(ctx, invite("alice", "bob"));
		// Mirror createRouterInfo's route resolution.
		String r0 = ctx.resolve(t.getRoutes()[0]);
		String r1 = ctx.resolve(t.getRoutes()[1]);
		check("route 0 built from extracted user", "sip:bob@special-proxy".equals(r0));
		check("route 1 built from extracted host", "sip:example.com".equals(r1));
	}

	private static void testCaptureAndCarryAcrossStates() {
		// Shared backing map = the stateInfo context carried across hops.
		Map<String, String> stateInfo = new HashMap<>();

		// Hop 1: state "null" captures To/From from the original INVITE.
		State init = new State();
		init.addSelector(new RegexSelector("From", "From", SIP_USER, null));
		init.addSelector(new RegexSelector("To", "To", SIP_USER, null));
		init.extract(new MemoryContext(stateInfo), invite("alice", "bob"));

		// Hop 2: state "screening" re-captures From (app rewrote it to anonymous),
		// but the original ${To.user} captured at hop 1 must still be available.
		State screening = new State();
		screening.addSelector(new RegexSelector("callerNow", "From", SIP_USER, null));
		Context ctx2 = new MemoryContext(stateInfo);
		screening.extract(ctx2, invite("anonymous", "bob"));

		check("carry: original To.user survives to hop 2", "bob".equals(ctx2.resolve("${To.user}")));
		check("carry: original From.user survives to hop 2", "alice".equals(ctx2.resolve("${From.user}")));
		check("fresh: rewritten caller captured under new name", "anonymous".equals(ctx2.resolve("${callerNow.user}")));

		Transition anon = new Transition().setWhen("${callerNow.user} == 'anonymous'").setNext("b2bua")
				.setRoutes(new String[] { "sip:${To.user}@anon-gw" });
		check("carry: hop-2 condition fires on rewritten caller", anon.matches(ctx2));
		check("carry: hop-2 route uses carried To.user", "sip:bob@anon-gw".equals(ctx2.resolve(anon.getRoutes()[0])));
	}

	private static void testUnconditionalTransition() {
		Transition t = new Transition().setNext("b2bua");
		check("no when => unconditional match", t.matches(new MemoryContext()));
		Transition empty = new Transition().setWhen("").setNext("b2bua");
		check("empty when => unconditional match", empty.matches(new MemoryContext()));
	}

	private static void testFirstMatchWins() {
		State s = new State();
		s.addSelector(new RegexSelector("To", "To", SIP_USER, null));
		Trigger trig = s.getTrigger("INVITE");
		trig.createTransition("special").setId("special").setWhen("${To.user} == 'bob'");
		trig.createTransition("default").setId("default");

		Context ctx = new MemoryContext();
		s.extract(ctx, invite("alice", "bob"));
		String chosen = null;
		for (Transition t : trig.getTransitions()) {
			if (t.matches(ctx)) { chosen = t.getId(); break; }
		}
		check("first matching transition wins (bob)", "special".equals(chosen));

		Context ctx2 = new MemoryContext();
		s.extract(ctx2, invite("alice", "carol"));
		String chosen2 = null;
		for (Transition t : trig.getTransitions()) {
			if (t.matches(ctx2)) { chosen2 = t.getId(); break; }
		}
		check("falls through to unconditional default (carol)", "default".equals(chosen2));
	}

	private static void testSampleConfigShape() {
		AppRouterConfigurationSample cfg = new AppRouterConfigurationSample();
		check("sample has null state", cfg.getStates().get("null") != null);
		check("sample null state has selectors", !cfg.getStates().get("null").getSelectors().isEmpty());
		check("sample defaultApplication set", "b2bua".equals(cfg.getDefaultApplication()));
		State init = cfg.getStates().get("null");

		// INV-bob (found by id — it no longer leads the trigger; the two
		// ingress dispatch transitions do) still fires for alice->bob.
		Context ctx = new MemoryContext();
		init.extract(ctx, invite("alice", "bob"));
		Transition bob = findTransition(init, "INV-bob");
		check("sample INV-bob fires for alice->bob", bob != null && bob.matches(ctx));
		check("sample INV-bob route resolves", "sip:bob@special-proxy".equals(ctx.resolve(bob.getRoutes()[0])));

		// The two ingress SBCs lead the INVITE trigger and classify by source
		// subnet via the insubnet operator (real CIDR, ipaddress-backed).
		java.util.List<Transition> inv = init.getTrigger("INVITE").getTransitions();
		check("sample dispatch-Atlanta leads", "dispatch-Atlanta".equals(inv.get(0).getId())
				&& "Atlanta".equals(inv.get(0).getNext()));
		check("sample dispatch-Dallas second", "dispatch-Dallas".equals(inv.get(1).getId())
				&& "Dallas".equals(inv.get(1).getNext()));

		Context atl = new MemoryContext(); atl.put("originIP", "10.20.5.9");
		Context dal = new MemoryContext(); dal.put("originIP", "10.30.5.9");
		check("sample Atlanta subnet matches its dispatch", findTransition(init, "dispatch-Atlanta").matches(atl));
		check("sample Atlanta IP misses Dallas dispatch", !findTransition(init, "dispatch-Dallas").matches(atl));
		check("sample Dallas subnet matches its dispatch", findTransition(init, "dispatch-Dallas").matches(dal));

		// The ingress entry states exist with their own routing.
		check("sample Atlanta entry state exists", cfg.getStates().get("Atlanta") != null);
		check("sample Dallas entry state exists", cfg.getStates().get("Dallas") != null);
		// And they're marked as ingresses in the diagram.
		check("sample diagram marks Atlanta + Dallas ingresses",
				cfg.getDiagram() != null && cfg.getDiagram().getIngresses() != null
						&& cfg.getDiagram().getIngresses().containsKey("Atlanta")
						&& cfg.getDiagram().getIngresses().containsKey("Dallas"));
	}

	private static void testPseudoVariables() {
		Context ctx = new MemoryContext();
		AppRouter.publishPseudoVariables(ctx, "INVITE", "sip:bob@example.com", "NEW",
				"NEUTRAL", "null", "a84b4c76e66710@pc33.example.com");

		check("pseudo: method", "INVITE".equals(ctx.resolve("${method}")));
		check("pseudo: requestURI", "sip:bob@example.com".equals(ctx.resolve("${requestURI}")));
		check("pseudo: directive", "NEW".equals(ctx.resolve("${directive}")));
		check("pseudo: previousApp", "null".equals(ctx.resolve("${previousApp}")));

		int hour = Integer.parseInt(ctx.resolve("${hour}"));
		check("pseudo: hour in range", hour >= 0 && hour <= 23);
		check("pseudo: dayOfWeek named", ctx.resolve("${dayOfWeek}").matches("[A-Z]+"));

		int bucket = Integer.parseInt(ctx.resolve("${hash100}"));
		check("pseudo: hash100 in range", bucket >= 0 && bucket <= 99);

		// Stability: the same Call-ID always lands in the same bucket.
		Context ctx2 = new MemoryContext();
		AppRouter.publishPseudoVariables(ctx2, "INVITE", "", "", "", "null",
				"a84b4c76e66710@pc33.example.com");
		check("pseudo: hash100 stable per Call-ID", ctx.resolve("${hash100}").equals(ctx2.resolve("${hash100}")));

		// Canary condition is expressible.
		Transition canary = new Transition().setWhen("${hash100} < 100").setNext("b2bua");
		check("pseudo: canary condition evaluates", canary.matches(ctx));

		// null-tolerance (no Call-ID → no hash100, nothing throws)
		Context ctx3 = new MemoryContext();
		AppRouter.publishPseudoVariables(ctx3, null, null, null, null, null, null);
		check("pseudo: null-tolerant", ctx3.resolve("${hash100}").equals("${hash100}"));
	}

	private static void testTierTableClassification() {
		// The sample's tier table classifies alice as gold and the INV-gold
		// transition fires on it — tiering as data, end to end.
		AppRouterConfigurationSample cfg = new AppRouterConfigurationSample();
		State init = cfg.getStates().get("null");

		Context ctx = new MemoryContext();
		init.extract(ctx, invite("alice", "carol"));
		check("tier: alice classified gold", "gold".equals(ctx.resolve("${tier}")));
		check("tier: namespaced too", "gold".equals(ctx.resolve("${customerTier.tier}")));

		Transition gold = findTransition(init, "INV-gold");
		check("tier: INV-gold fires for gold caller", gold != null && gold.matches(ctx));
		check("tier: gold route resolves", "sip:alice@gold-trunk".equals(ctx.resolve(gold.getRoutes()[0])));

		// carol is in no tier row: INV-gold must not fire.
		Context ctx2 = new MemoryContext();
		init.extract(ctx2, invite("carol", "bob"));
		check("tier: unclassified caller not gold", gold != null && !gold.matches(ctx2));
	}

	private static void testMatchesOperatorInWhen() {
		AppRouterConfigurationSample cfg = new AppRouterConfigurationSample();
		State init = cfg.getStates().get("null");
		Transition tollfree = findTransition(init, "INV-tollfree");

		Context ctx = new MemoryContext();
		init.extract(ctx, invite("carol", "18005551212"));
		check("matches: toll-free callee fires", tollfree != null && tollfree.matches(ctx));

		Context ctx2 = new MemoryContext();
		init.extract(ctx2, invite("carol", "14085551212"));
		check("matches: ordinary callee does not fire", tollfree != null && !tollfree.matches(ctx2));
	}

	private static void testRegionSerialization() throws RuntimeException {
		try {
			com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			Transition t = new Transition().setId("r").setNext("b2bua")
					.setRegion(Transition.Region.TERMINATING);
			String json = mapper.writeValueAsString(t);
			check("region: serialized by name", json.contains("\"region\":\"TERMINATING\""));
			Transition back = mapper.readValue(json, Transition.class);
			check("region: round-trips", back.getRegion() == Transition.Region.TERMINATING);
			Transition plain = mapper.readValue("{\"id\":\"x\",\"next\":\"b2bua\"}", Transition.class);
			check("region: absent stays null (NEUTRAL at runtime)", plain.getRegion() == null);
		} catch (Exception e) {
			check("region: serialization threw " + e.getMessage(), false);
		}
	}

	private static void testEgressRouterInfo() {
		// A terminal transition (no `next`) that carries routes is an egress:
		// createRouterInfo with a null app name is the JSR-289 "selection
		// complete, route per these routes" signal the engine now emits.
		State s = new State();
		s.addSelector(new RegexSelector("To", "To", SIP_USER, null));
		Context ctx = new MemoryContext();
		s.extract(ctx, invite("alice", "bob"));

		Transition egress = new Transition().setId("offnet")
				.setRouteFinal(new String[] { "sip:${To.user}@carrier-trunk" });
		// No subscriber set, so passing a null request is safe.
		SipApplicationRouterInfo info = egress.createRouterInfo(null, new HashMap<String, String>(), ctx, null);
		check("egress: no next application (null name)", info.getNextApplicationName() == null);
		check("egress: route resolved from context", "sip:bob@carrier-trunk".equals(info.getRoutes()[0]));
		check("egress: ROUTE_FINAL applied", info.getRouteModifier() == SipRouteModifier.ROUTE_FINAL);

		Transition back = new Transition().setId("back")
				.setRouteBack(new String[] { "sip:reject@${originIP}" });
		Context ctx2 = new MemoryContext();
		ctx2.put("originIP", "10.20.5.9");
		SipApplicationRouterInfo binfo = back.createRouterInfo(null, new HashMap<String, String>(), ctx2, null);
		check("egress: ROUTE_BACK applied", binfo.getRouteModifier() == SipRouteModifier.ROUTE_BACK);
		check("egress: ROUTE_BACK route resolved", "sip:reject@10.20.5.9".equals(binfo.getRoutes()[0]));
	}

	private static void testSampleEgress() {
		AppRouterConfigurationSample cfg = new AppRouterConfigurationSample();

		// b2bua-callee off-net path is a terminal ROUTE_FINAL egress.
		Transition offnet = findTransition(cfg.getStates().get("b2bua-callee"), "B2B-offnet");
		check("sample B2B-offnet exists", offnet != null);
		check("sample B2B-offnet is terminal (no next)", offnet != null && offnet.getNext() == null);
		check("sample B2B-offnet is ROUTE_FINAL",
				offnet != null && offnet.getRouteModifier() == SipRouteModifier.ROUTE_FINAL);

		// screening anon path is a ROUTE_BACK egress: routes out, resumes at
		// b2bua (next), so `next` is the return state, not null.
		Transition anon = findTransition(cfg.getStates().get("screening"), "SCR-anon");
		check("sample SCR-anon exists", anon != null);
		check("sample SCR-anon is ROUTE_BACK",
				anon != null && anon.getRouteModifier() == SipRouteModifier.ROUTE_BACK);
		check("sample SCR-anon resumes at b2bua (next = return state)",
				anon != null && "b2bua".equals(anon.getNext()));

		// Diagram marks both exits; media-greeting carries its returnState.
		check("sample diagram marks to-carrier + media-greeting egresses",
				cfg.getDiagram() != null && cfg.getDiagram().getEgresses() != null
						&& cfg.getDiagram().getEgresses().containsKey("to-carrier")
						&& cfg.getDiagram().getEgresses().containsKey("media-greeting"));
		check("sample media-greeting egress returns to b2bua",
				cfg.getDiagram().getEgresses().get("media-greeting") != null
						&& "b2bua".equals(cfg.getDiagram().getEgresses().get("media-greeting").getReturnState()));
		check("sample to-carrier egress is ROUTE_FINAL (no returnState)",
				cfg.getDiagram().getEgresses().get("to-carrier") != null
						&& cfg.getDiagram().getEgresses().get("to-carrier").getReturnState() == null);
	}

	private static void testTwiceInvokedApp() {
		// A state's id (map key) is distinct from the app it invokes: two states
		// can share an app. State.appOrId resolves the app (its `app`, else the
		// id passed in).
		State plain = new State();
		check("appOrId: defaults to the id when app unset", "b2bua".equals(plain.appOrId("b2bua")));
		State callee = new State().setApp("b2bua");
		check("appOrId: uses app when set", "b2bua".equals(callee.appOrId("b2bua-callee")));

		// The sample demonstrates it end to end: two states, both running b2bua.
		AppRouterConfigurationSample cfg = new AppRouterConfigurationSample();
		State caller = cfg.getStates().get("b2bua");
		State callee2 = cfg.getStates().get("b2bua-callee");
		check("sample has both b2bua-leg states", caller != null && callee2 != null);
		check("sample b2bua (caller leg) runs app b2bua", caller != null && "b2bua".equals(caller.appOrId("b2bua")));
		check("sample b2bua-callee runs app b2bua", callee2 != null && "b2bua".equals(callee2.getApp()));
		Transition callerLeg = findTransition(caller, "B2B-caller-leg");
		check("sample caller leg routes to the callee-leg state id",
				callerLeg != null && "b2bua-callee".equals(callerLeg.getNext()));
	}

	private static void testMetricsCounters() {
		Fsmar3Metrics m = new Fsmar3Metrics();
		m.countRequest();
		m.countRequest();
		m.countDefaultFallback();
		m.countBypass();
		m.countCycle();
		m.countTransition("null", "INVITE", "INV-gold");
		m.countTransition("null", "INVITE", "INV-gold");
		m.countTransition("screening", "INVITE", null);

		check("metrics: requests", m.getRequestsRouted() == 2);
		check("metrics: fallbacks", m.getDefaultApplicationFallbacks() == 1);
		check("metrics: bypasses", m.getUndeployedBypasses() == 1);
		check("metrics: cycles", m.getRoutingCyclesDetected() == 1);

		String[] hits = m.getTransitionHits();
		check("metrics: two distinct transitions", hits.length == 2);
		check("metrics: gold counted twice",
				java.util.Arrays.asList(hits).contains("null/INVITE/INV-gold = 2"));
		check("metrics: null id renders as dash",
				java.util.Arrays.asList(hits).contains("screening/INVITE/- = 1"));

		m.resetCounters();
		check("metrics: reset", m.getRequestsRouted() == 0 && m.getTransitionHits().length == 0);
	}

	private static Transition findTransition(State state, String id) {
		for (Transition t : state.getTrigger("INVITE").getTransitions()) {
			if (id.equals(t.getId())) return t;
		}
		return null;
	}

	private static void check(String name, boolean ok) {
		if (ok) { passed++; System.out.println("  PASS  " + name); }
		else { failed++; System.out.println("  FAIL  " + name); }
	}

	private static void summary() {
		System.out.println("FsmarRoutingSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}
}
