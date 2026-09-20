package org.vorpal.blade.framework.cors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SameOriginFilterTest {

	private static final Set<String> NONE = Collections.emptySet();

	private static boolean post(String origin, String referer, String host, String forwardedHost, Set<String> allowed) {
		return SameOriginFilter.allowed("POST", null, origin, referer, host, forwardedHost, allowed);
	}

	@Test
	void sameSitePasses() {
		assertTrue(post("https://admin.example.com:7002", null, "admin.example.com:7002", null, NONE));
		assertTrue(post("https://admin.example.com", null, "admin.example.com:443", null, NONE));
		assertTrue(post("HTTPS://Admin.Example.com:7002", null, "admin.example.com:7002", null, NONE));
	}

	@Test
	void crossSiteIsRefused() {
		assertFalse(post("https://evil.example.net", null, "admin.example.com:7002", null, NONE));
		assertFalse(post("https://admin.example.com:8443", null, "admin.example.com:7002", null, NONE));
		assertFalse(post("null", null, "admin.example.com:7002", null, NONE));
	}

	@Test
	void refererIsTheFallbackWitness() {
		assertTrue(post(null, "https://admin.example.com:7002/blade/flow/", "admin.example.com:7002", null, NONE));
		assertFalse(post(null, "https://evil.example.net/page", "admin.example.com:7002", null, NONE));
	}

	@Test
	void noWitnessMeansNotABrowserAndPasses() {
		assertTrue(post(null, null, "engine1:8001", null, NONE));
	}

	@Test
	void readsPassAndWebSocketUpgradesAreChecked() {
		assertTrue(SameOriginFilter.allowed("GET", null, "https://evil.example.net", null, "admin:7002", null, NONE));
		assertFalse(SameOriginFilter.allowed("GET", "websocket", "https://evil.example.net", null, "admin:7002", null, NONE));
		assertTrue(SameOriginFilter.allowed("GET", "websocket", "https://admin:7002", null, "admin:7002", null, NONE));
	}

	@Test
	void proxyHostAndAllowlistPass() {
		assertTrue(post("https://blade.example.com", null, "10.0.0.5:7002", "blade.example.com, 10.1.1.1", NONE));
		Set<String> allowed = CorsFilter.parseOrigins("https://admin.example.com:7002");
		assertTrue(post("https://admin.example.com:7002", null, "engine1.example.com:8002", null, allowed));
	}
}
