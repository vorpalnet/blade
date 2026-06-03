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

	private static void check(String name, boolean ok) {
		if (ok) { passed++; System.out.println("  PASS  " + name); }
		else { failed++; System.out.println("  FAIL  " + name); }
	}

	private static void summary() {
		System.out.println("FsmarRoutingSmokeTest: " + passed + " passed, " + failed + " failed");
		if (failed > 0) System.exit(1);
	}
}
