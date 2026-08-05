package org.vorpal.blade.applications.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/// Unit tests for [ApiHttp] — the pure URL-building / sanitizing helpers behind
/// the spec proxy and discovery probe.
public class ApiHttpTest {

	@Test
	public void sanitizeAppAcceptsLegitimateContextRoots() {
		assertEquals("transfer", ApiHttp.sanitizeApp("transfer"), "plain service context-root");
		assertEquals("transfer", ApiHttp.sanitizeApp("/transfer"), "leading slash stripped");
		assertEquals("transfer", ApiHttp.sanitizeApp("  transfer  "), "trimmed");
		assertEquals("blade/configurator", ApiHttp.sanitizeApp("blade/configurator"), "blade/ admin context-root");
		assertEquals("proxy-block", ApiHttp.sanitizeApp("proxy-block"), "hyphenated name");
	}

	@Test
	public void sanitizeAppRejectsEscapeAttempts() {
		assertNull(ApiHttp.sanitizeApp(null), "null");
		assertNull(ApiHttp.sanitizeApp(""), "empty");
		assertNull(ApiHttp.sanitizeApp("/"), "only a slash");
		assertNull(ApiHttp.sanitizeApp("../etc/passwd"), "path traversal");
		assertNull(ApiHttp.sanitizeApp("a/../../b"), "embedded traversal");
		assertNull(ApiHttp.sanitizeApp("http://evil.com/x"), "absolute URL with scheme");
		assertNull(ApiHttp.sanitizeApp("host:8080"), "colon (authority) rejected");
		assertNull(ApiHttp.sanitizeApp("user@host"), "at-sign rejected");
		assertNull(ApiHttp.sanitizeApp("a\\b"), "backslash rejected");
		assertNull(ApiHttp.sanitizeApp("a b"), "space rejected");
	}

	@Test
	public void protocolRelativeCollapsesToPathSegment() {
		// The host is always the configured base, so a bare dotted name is just
		// a harmless path segment under it (resolves to a 404, not another host).
		assertEquals("evil.com", ApiHttp.sanitizeApp("//evil.com"));
	}

	@Test
	public void specUrl() {
		assertEquals("http://h:8001/transfer/resources/openapi.json",
				ApiHttp.specUrl("http://h:8001", "transfer", "json"), "json spec url");
		assertEquals("http://h:8001/blade/configurator/resources/openapi.yaml",
				ApiHttp.specUrl("http://h:8001", "blade/configurator", "yaml"), "yaml spec url, nested root");
	}

	@Test
	public void normalizeFormat() {
		assertEquals("yaml", ApiHttp.normalizeFormat("yaml"));
		assertEquals("yaml", ApiHttp.normalizeFormat("YAML"), "case-insensitive");
		assertEquals("json", ApiHttp.normalizeFormat("json"));
		assertEquals("json", ApiHttp.normalizeFormat(null), "null defaults to json");
		assertEquals("json", ApiHttp.normalizeFormat("xml"), "unknown defaults to json");
	}
}
