package org.vorpal.blade.framework.v3.configuration.routing;

import org.vorpal.blade.framework.v3.FakeContext;
import org.vorpal.blade.framework.v3.configuration.MatchStrategy;

/// Smoke-test driver covering [TableRouting] (including multi-table
/// fallback), [ConditionalRouting], [DirectRouting], and
/// [ConditionalHeader].
public final class RoutingSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testTableRoutingSingleTable();
		testTableRoutingMultiTable();
		testTableRoutingFallthroughToDefault();
		testTableRoutingRangeMatch();
		testConditionalRoutingBasic();
		testConditionalRoutingCombinators();
		testConditionalRoutingFallthrough();
		testConditionalRoutingMalformedClauseSkipped();
		testDirectRouting();
		testConditionalHeaderApplies();
		testConditionalHeaderSkips();

		summary();
	}

	private static void testTableRoutingSingleTable() {
		RoutingTable rt = new RoutingTable();
		rt.setMatch(MatchStrategy.hash);
		rt.setKeyExpression("${action}");
		Route allow = new Route("sip:allowed@pbx");
		Route block = new Route("sip:blocked@pbx");
		rt.getRoutes().put("allow", allow);
		rt.getRoutes().put("block", block);

		TableRouting tr = new TableRouting();
		tr.addTable(rt);

		FakeContext ctx = new FakeContext().set("action", "allow");
		check("single.hit.allow", allow == tr.decide(ctx));

		ctx.set("action", "block");
		check("single.hit.block", block == tr.decide(ctx));
	}

	private static void testTableRoutingMultiTable() {
		// First table: hash on action. Second: prefix on destNum.
		RoutingTable byAction = new RoutingTable();
		byAction.setMatch(MatchStrategy.hash);
		byAction.setKeyExpression("${action}");
		Route block = new Route("sip:rejected@pbx");
		byAction.getRoutes().put("block", block);

		RoutingTable byPrefix = new RoutingTable();
		byPrefix.setMatch(MatchStrategy.prefix);
		byPrefix.setKeyExpression("${destNum}");
		Route tollfree = new Route("sip:${destNum}@tollfree");
		Route domestic = new Route("sip:${destNum}@domestic");
		byPrefix.getRoutes().put("1800", tollfree);
		byPrefix.getRoutes().put("1", domestic);

		TableRouting tr = new TableRouting();
		tr.addTable(byAction);
		tr.addTable(byPrefix);
		Route dflt = new Route("sip:default@pbx");
		tr.setDefaultRoute(dflt);

		FakeContext ctx = new FakeContext();

		// First-table hit on action=block
		ctx.set("action", "block").set("destNum", "18005551234");
		check("multi.firstWins", block == tr.decide(ctx));

		// First table misses (action=allow), second wins on 1800 prefix
		ctx.set("action", "allow").set("destNum", "18005551234");
		check("multi.fallThrough.tollfree", tollfree == tr.decide(ctx));

		// First misses, second falls back to broader 1 prefix
		ctx.set("destNum", "12125550000");
		check("multi.fallThrough.domestic", domestic == tr.decide(ctx));

		// Both miss → default
		ctx.set("destNum", "44207");
		check("multi.bothMiss.default", dflt == tr.decide(ctx));
	}

	private static void testTableRoutingFallthroughToDefault() {
		RoutingTable rt = new RoutingTable();
		rt.setMatch(MatchStrategy.hash);
		rt.setKeyExpression("${action}");
		rt.getRoutes().put("allow", new Route("sip:a@pbx"));

		TableRouting tr = new TableRouting();
		tr.addTable(rt);
		Route dflt = new Route("sip:default@pbx");
		tr.setDefaultRoute(dflt);

		FakeContext ctx = new FakeContext();
		// No match → default
		ctx.set("action", "nothing-like-allow");
		check("default.onMiss", dflt == tr.decide(ctx));

		// Unresolved key → default
		ctx.clear();
		check("default.onUnresolved", dflt == tr.decide(ctx));

		// No default + no match → null
		tr.setDefaultRoute(null);
		check("null.onMiss.noDefault", tr.decide(ctx) == null);
	}

	private static void testTableRoutingRangeMatch() {
		RoutingTable rt = new RoutingTable();
		rt.setMatch(MatchStrategy.range);
		rt.setKeyExpression("${priority}");
		Route low = new Route("sip:low@pbx");
		Route mid = new Route("sip:mid@pbx");
		Route high = new Route("sip:high@pbx");
		rt.getRoutes().put("0-33", low);
		rt.getRoutes().put("34-66", mid);
		rt.getRoutes().put("67-100", high);

		TableRouting tr = new TableRouting();
		tr.addTable(rt);

		FakeContext ctx = new FakeContext();
		ctx.set("priority", "10");
		check("range.low", low == tr.decide(ctx));
		ctx.set("priority", "50");
		check("range.mid", mid == tr.decide(ctx));
		ctx.set("priority", "95");
		check("range.high", high == tr.decide(ctx));
	}

	private static void testConditionalRoutingBasic() {
		ConditionalRouting cr = new ConditionalRouting();
		Route blocked = new Route("sip:blocked@pbx");
		Route allowed = new Route("sip:allowed@pbx");
		cr.addClause("${action} == block", blocked);
		cr.addClause("${action} == allow", allowed);
		Route dflt = new Route("sip:default@pbx");
		cr.setDefaultRoute(dflt);

		FakeContext ctx = new FakeContext();
		ctx.set("action", "allow");
		check("cond.basic.allow", allowed == cr.decide(ctx));
		ctx.set("action", "block");
		check("cond.basic.block", blocked == cr.decide(ctx));
		ctx.set("action", "something-else");
		check("cond.basic.default", dflt == cr.decide(ctx));
	}

	private static void testConditionalRoutingCombinators() {
		ConditionalRouting cr = new ConditionalRouting();
		Route premium = new Route("sip:premium@pbx");
		Route standard = new Route("sip:standard@pbx");
		Route rejected = new Route("sip:rejected@pbx");
		cr.addClause("${action} == block", rejected);
		cr.addClause("${customerTier} == premium && ${score} >= 80", premium);
		cr.addClause("${action} == allow", standard);
		cr.setDefaultRoute(new Route("sip:default@pbx"));

		FakeContext ctx = new FakeContext();
		ctx.set("action", "allow").set("customerTier", "premium").set("score", "90");
		check("cond.combinators.premium", premium == cr.decide(ctx));

		ctx.set("score", "75");  // fails the && premium clause
		check("cond.combinators.standard", standard == cr.decide(ctx));

		ctx.set("action", "block");
		check("cond.combinators.blockWinsFirst", rejected == cr.decide(ctx));
	}

	private static void testConditionalRoutingFallthrough() {
		ConditionalRouting cr = new ConditionalRouting();
		cr.addClause("${never} == set", new Route("sip:never@pbx"));
		Route dflt = new Route("sip:default@pbx");
		cr.setDefaultRoute(dflt);

		FakeContext ctx = new FakeContext();
		check("cond.fallthrough.default", dflt == cr.decide(ctx));
	}

	private static void testConditionalRoutingMalformedClauseSkipped() {
		ConditionalRouting cr = new ConditionalRouting();
		// Malformed expression — should throw at parse, caught by Clause.matches()
		cr.addClause("this is not a valid expression !!!", new Route("sip:bad@pbx"));
		Route good = new Route("sip:good@pbx");
		cr.addClause("${flag} == true", good);

		FakeContext ctx = new FakeContext().set("flag", "true");
		check("cond.malformed.skipped", good == cr.decide(ctx));
	}

	private static void testDirectRouting() {
		DirectRouting dr = new DirectRouting();
		dr.setRequestUri("sip:${destNum}@pbx");
		dr.setDescription("always");

		FakeContext ctx = new FakeContext().set("destNum", "18005551234");
		Route r = dr.decide(ctx);
		check("direct.notNull", r != null);
		check("direct.requestUri",
				r != null && "sip:${destNum}@pbx".equals(r.getRequestUri()));
	}

	private static void testConditionalHeaderApplies() {
		ConditionalHeader ch = new ConditionalHeader(
				"X-Priority", "high", "${tier} == premium");
		FakeContext ctx = new FakeContext().set("tier", "premium");
		check("condHeader.applies", ch.shouldApply(ctx));
	}

	private static void testConditionalHeaderSkips() {
		ConditionalHeader ch = new ConditionalHeader(
				"X-Priority", "high", "${tier} == premium");
		FakeContext ctx = new FakeContext().set("tier", "standard");
		check("condHeader.skips.wrongTier", !ch.shouldApply(ctx));

		// Null/empty when expression → always apply
		ConditionalHeader always = new ConditionalHeader("X-Static", "v", "");
		check("condHeader.empty.always", always.shouldApply(ctx));

		// Malformed expression → safe false
		ConditionalHeader bad = new ConditionalHeader("X-Bad", "v", "???bogus");
		check("condHeader.malformed.false", !bad.shouldApply(ctx));
	}

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("PASS  " + name);
		} else {
			failed++;
			System.out.println("FAIL  " + name);
		}
	}

	private static void summary() {
		System.out.println();
		System.out.println("Passed: " + passed + " / " + (passed + failed));
		if (failed > 0) System.exit(1);
	}
}
