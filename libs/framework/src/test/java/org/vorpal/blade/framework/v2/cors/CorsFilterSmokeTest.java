package org.vorpal.blade.framework.v2.cors;

import java.util.Set;

/// Smoke-test driver for [CorsFilter#parseOrigins] — the allowlist parser that
/// decides whether the filter is a no-op. Same `main()` convention as the other
/// framework smoke tests. Exits non-zero on the first failed expectation.
///
/// ```
/// java -cp target/classes:target/test-classes \
///   org.vorpal.blade.framework.v2.cors.CorsFilterSmokeTest
/// ```
public class CorsFilterSmokeTest {

	private static int failures = 0;

	public static void main(String[] args) {
		// Unset / blank ⇒ empty set ⇒ filter is a complete no-op.
		empty(CorsFilter.parseOrigins(null), "null");
		empty(CorsFilter.parseOrigins(""), "empty string");
		empty(CorsFilter.parseOrigins("   "), "whitespace only");
		empty(CorsFilter.parseOrigins(",, ,"), "only separators");

		Set<String> one = CorsFilter.parseOrigins("https://admin.example.com:7002");
		size(one, 1, "single origin");
		has(one, "https://admin.example.com:7002", "single origin value");

		// Comma-separated, trimmed, blanks dropped.
		Set<String> many = CorsFilter.parseOrigins(" https://a.com , https://b.com:7002 ,");
		size(many, 2, "two origins, trimmed");
		has(many, "https://a.com", "first origin trimmed");
		has(many, "https://b.com:7002", "second origin trimmed");

		if (failures > 0) {
			System.out.println("CorsFilterSmokeTest: FAIL (" + failures + " failed)");
			System.exit(1);
		}
		System.out.println("CorsFilterSmokeTest: PASS");
	}

	private static void empty(Set<String> actual, String what) {
		if (actual == null || !actual.isEmpty()) {
			System.out.println("  FAIL [" + what + "] expected empty set but was <" + actual + ">");
			failures++;
		}
	}

	private static void size(Set<String> actual, int expected, String what) {
		if (actual == null || actual.size() != expected) {
			System.out.println("  FAIL [" + what + "] expected size " + expected + " but was <" + actual + ">");
			failures++;
		}
	}

	private static void has(Set<String> actual, String value, String what) {
		if (actual == null || !actual.contains(value)) {
			System.out.println("  FAIL [" + what + "] expected to contain <" + value + "> but was <" + actual + ">");
			failures++;
		}
	}
}
