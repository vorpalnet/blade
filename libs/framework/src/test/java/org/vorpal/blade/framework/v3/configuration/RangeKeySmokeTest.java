package org.vorpal.blade.framework.v3.configuration;

/// Smoke-test driver for [RangeKey].
public final class RangeKeySmokeTest {
	private static int passed;
	private static int failed;

	public static void main(String[] args) {
		// Simple ranges — inclusive bounds
		check("simple.low", RangeKey.contains("8-17", 8));
		check("simple.high", RangeKey.contains("8-17", 17));
		check("simple.middle", RangeKey.contains("8-17", 12));
		check("simple.below", !RangeKey.contains("8-17", 7));
		check("simple.above", !RangeKey.contains("8-17", 18));

		// Single-value range
		check("single.match", RangeKey.contains("5-5", 5));
		check("single.miss.low", !RangeKey.contains("5-5", 4));
		check("single.miss.high", !RangeKey.contains("5-5", 6));

		// Wider ranges
		check("wide.match.low", RangeKey.contains("100-999", 100));
		check("wide.match.mid", RangeKey.contains("100-999", 500));
		check("wide.match.high", RangeKey.contains("100-999", 999));

		// Whitespace tolerance
		check("ws.around.dash", RangeKey.contains("8 - 17", 10));
		check("ws.leading", RangeKey.contains("  8-17", 10));
		check("ws.trailing", RangeKey.contains("8-17  ", 10));

		// Malformed — silently false
		check("malformed.noDash", !RangeKey.contains("17", 10));
		check("malformed.empty", !RangeKey.contains("", 10));
		check("malformed.null", !RangeKey.contains(null, 10));
		check("malformed.nonNumeric", !RangeKey.contains("foo-bar", 10));
		check("malformed.trailingDash", !RangeKey.contains("8-", 10));
		check("malformed.leadingDash", !RangeKey.contains("-8", 10));

		// Boundary values
		check("boundary.zero", RangeKey.contains("0-10", 0));
		check("boundary.max", RangeKey.contains("1-" + Long.MAX_VALUE, Long.MAX_VALUE));

		// Integer overflow safety — Long not int
		check("long.range", RangeKey.contains("1000000000-9999999999", 5000000000L));

		summary();
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
