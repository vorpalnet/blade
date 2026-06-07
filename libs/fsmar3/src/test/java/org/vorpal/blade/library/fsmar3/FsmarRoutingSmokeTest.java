package org.vorpal.blade.library.fsmar3;

import java.util.HashMap;
import java.util.Map;

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
		// Drive the sample's INVITE transitions for alice->bob.
		State init = cfg.getStates().get("null");
		Context ctx = new MemoryContext();
		init.extract(ctx, invite("alice", "bob"));
		Transition first = init.getTrigger("INVITE").getTransitions().get(0);
		check("sample INV-bob fires for alice->bob", "INV-bob".equals(first.getId()) && first.matches(ctx));
		check("sample INV-bob route resolves", "sip:bob@special-proxy".equals(ctx.resolve(first.getRoutes()[0])));
	}

	private static void testPseudoVariables() {
		Context ctx = new MemoryContext();
		AppRouter.publishPseudoVariables(ctx, "INVITE", "sip:bob@example.com", "NEW",
				"NEUTRAL", "null", "a84b4c76e66710@pc33.example.com");

		check("pseudo: method", "INVITE".equals(ctx.resolve("${method}")));
		check("pseudo: requestUri", "sip:bob@example.com".equals(ctx.resolve("${requestUri}")));
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
