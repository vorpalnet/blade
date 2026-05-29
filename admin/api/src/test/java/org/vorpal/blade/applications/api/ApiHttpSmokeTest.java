package org.vorpal.blade.applications.api;

/// Smoke-test driver for [ApiHttp] — the pure URL-building / sanitizing helpers
/// behind the spec proxy and discovery probe. Same `main()` convention as the
/// framework's other smoke tests (no JUnit on the classpath). Exits non-zero on
/// the first failed expectation.
///
/// Run after compiling the module:
/// ```
/// java -cp target/classes:target/test-classes \
///   org.vorpal.blade.applications.api.ApiHttpSmokeTest
/// ```
public class ApiHttpSmokeTest {

	private static int failures = 0;

	public static void main(String[] args) {
		// --- sanitizeApp: accept legitimate context-roots -------------------
		eq("transfer", ApiHttp.sanitizeApp("transfer"), "plain service context-root");
		eq("transfer", ApiHttp.sanitizeApp("/transfer"), "leading slash stripped");
		eq("transfer", ApiHttp.sanitizeApp("  transfer  "), "trimmed");
		eq("blade/configurator", ApiHttp.sanitizeApp("blade/configurator"), "blade/ admin context-root");
		eq("proxy-block", ApiHttp.sanitizeApp("proxy-block"), "hyphenated name");

		// --- sanitizeApp: reject escape attempts ----------------------------
		isNull(ApiHttp.sanitizeApp(null), "null");
		isNull(ApiHttp.sanitizeApp(""), "empty");
		isNull(ApiHttp.sanitizeApp("/"), "only a slash");
		isNull(ApiHttp.sanitizeApp("../etc/passwd"), "path traversal");
		isNull(ApiHttp.sanitizeApp("a/../../b"), "embedded traversal");
		isNull(ApiHttp.sanitizeApp("http://evil.com/x"), "absolute URL with scheme");
		isNull(ApiHttp.sanitizeApp("host:8080"), "colon (authority) rejected");
		isNull(ApiHttp.sanitizeApp("user@host"), "at-sign rejected");
		isNull(ApiHttp.sanitizeApp("a\\b"), "backslash rejected");
		isNull(ApiHttp.sanitizeApp("a b"), "space rejected");

		// The host is always the configured base, so a bare dotted name is just
		// a harmless path segment under it (resolves to a 404, not another host).
		eq("evil.com", ApiHttp.sanitizeApp("//evil.com"), "protocol-relative collapses to a path segment");

		// --- specUrl --------------------------------------------------------
		eq("http://h:8001/transfer/resources/openapi.json",
				ApiHttp.specUrl("http://h:8001", "transfer", "json"), "json spec url");
		eq("http://h:8001/blade/configurator/resources/openapi.yaml",
				ApiHttp.specUrl("http://h:8001", "blade/configurator", "yaml"), "yaml spec url, nested root");

		// --- normalizeFormat ------------------------------------------------
		eq("yaml", ApiHttp.normalizeFormat("yaml"), "yaml");
		eq("yaml", ApiHttp.normalizeFormat("YAML"), "YAML case-insensitive");
		eq("json", ApiHttp.normalizeFormat("json"), "json");
		eq("json", ApiHttp.normalizeFormat(null), "null defaults to json");
		eq("json", ApiHttp.normalizeFormat("xml"), "unknown defaults to json");

		if (failures > 0) {
			System.out.println("ApiHttpSmokeTest: FAIL (" + failures + " failed)");
			System.exit(1);
		}
		System.out.println("ApiHttpSmokeTest: PASS");
	}

	private static void eq(String expected, String actual, String what) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			System.out.println("  FAIL [" + what + "] expected <" + expected + "> but was <" + actual + ">");
			failures++;
		}
	}

	private static void isNull(String actual, String what) {
		if (actual != null) {
			System.out.println("  FAIL [" + what + "] expected null but was <" + actual + ">");
			failures++;
		}
	}
}
