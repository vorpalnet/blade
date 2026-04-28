package org.vorpal.blade.framework.v3.configuration.translations;

import org.vorpal.blade.framework.v3.FakeContext;
import org.vorpal.blade.framework.v3.configuration.MatchStrategy;

/// Smoke-test driver for [TranslationTable] lookup across the three
/// match strategies — hash, prefix, range.
public final class TranslationTableSmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		testHashMatch();
		testPrefixMatch();
		testRangeMatch();
		testUnresolvedKey();
		testEmptyTable();
		testDefaultStrategyIsHash();

		summary();
	}

	private static void testHashMatch() {
		TranslationTable t = new TranslationTable();
		t.setMatch(MatchStrategy.hash);
		t.setKeyExpression("${key}");
		t.createTranslation("foo").put("value", "FOO");
		t.createTranslation("bar").put("value", "BAR");

		FakeContext ctx = new FakeContext();
		ctx.set("key", "foo");
		Translation hit = t.lookup(ctx);
		check("hash.match.foo", hit != null && "FOO".equals(hit.getExtras().get("value")));

		ctx.set("key", "bar");
		check("hash.match.bar", "BAR".equals(t.lookup(ctx).getExtras().get("value")));

		ctx.set("key", "baz");
		check("hash.miss", t.lookup(ctx) == null);
	}

	private static void testPrefixMatch() {
		TranslationTable t = new TranslationTable();
		t.setMatch(MatchStrategy.prefix);
		t.setKeyExpression("${destNum}");
		t.createTranslation("1").put("region", "NANP");
		t.createTranslation("1816").put("region", "KansasCity");
		t.createTranslation("44").put("region", "UK");

		FakeContext ctx = new FakeContext();
		ctx.set("destNum", "18165551234");
		check("prefix.specific", "KansasCity".equals(t.lookup(ctx).getExtras().get("region")));

		ctx.set("destNum", "12125550000");
		check("prefix.nanp.fallback", "NANP".equals(t.lookup(ctx).getExtras().get("region")));

		ctx.set("destNum", "442075551234");
		check("prefix.uk", "UK".equals(t.lookup(ctx).getExtras().get("region")));

		ctx.set("destNum", "33123456789");
		check("prefix.no.match", t.lookup(ctx) == null);
	}

	private static void testRangeMatch() {
		TranslationTable t = new TranslationTable();
		t.setMatch(MatchStrategy.range);
		t.setKeyExpression("${hour}");
		t.createTranslation("0-7").put("shift", "overnight");
		t.createTranslation("8-17").put("shift", "business");
		t.createTranslation("18-23").put("shift", "evening");

		FakeContext ctx = new FakeContext();
		ctx.set("hour", "9");
		check("range.business", "business".equals(t.lookup(ctx).getExtras().get("shift")));

		ctx.set("hour", "3");
		check("range.overnight", "overnight".equals(t.lookup(ctx).getExtras().get("shift")));

		ctx.set("hour", "20");
		check("range.evening", "evening".equals(t.lookup(ctx).getExtras().get("shift")));

		// Boundary values
		ctx.set("hour", "8");
		check("range.boundary.low", "business".equals(t.lookup(ctx).getExtras().get("shift")));
		ctx.set("hour", "17");
		check("range.boundary.high", "business".equals(t.lookup(ctx).getExtras().get("shift")));

		// Out of range
		ctx.set("hour", "24");
		check("range.miss", t.lookup(ctx) == null);

		// Non-numeric key
		ctx.set("hour", "foo");
		check("range.nonNumeric", t.lookup(ctx) == null);
	}

	private static void testUnresolvedKey() {
		TranslationTable t = new TranslationTable();
		t.setMatch(MatchStrategy.hash);
		t.setKeyExpression("${missingVar}");
		t.createTranslation("foo").put("value", "FOO");

		FakeContext ctx = new FakeContext();  // no vars set
		check("unresolved.key.returns.null", t.lookup(ctx) == null);
	}

	private static void testEmptyTable() {
		TranslationTable t = new TranslationTable();
		t.setKeyExpression("${key}");
		FakeContext ctx = new FakeContext().set("key", "anything");
		check("empty.table", t.lookup(ctx) == null);
	}

	private static void testDefaultStrategyIsHash() {
		TranslationTable t = new TranslationTable();
		// no setMatch() call — should default to hash
		t.setKeyExpression("${key}");
		t.createTranslation("x").put("v", "X");
		FakeContext ctx = new FakeContext().set("key", "x");
		check("default.is.hash", "X".equals(t.lookup(ctx).getExtras().get("v")));
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
