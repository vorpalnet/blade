package org.vorpal.blade.framework.v3.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/// The masking a token fetch's bodies get before they join a trace.
class HttpStepsTest {

	@Test
	void aTokenRequestKeepsItsGrantButNotItsSecrets() {
		String masked = HttpSteps.maskForm(
				"grant_type=password&username=alice&password=s3cret&client_id=blade&client_secret=xyz&scope=read");
		assertEquals("grant_type=password&username=alice&password=********&client_id=blade"
				+ "&client_secret=********&scope=read", masked);
	}

	@Test
	void anAssertionGrantIsMasked() {
		String masked = HttpSteps.maskForm(
				"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=eyJhbGciOi.x.y");
		assertFalse(masked.contains("eyJ"));
	}

	@Test
	void aTokenAnswerKeepsItsLifetimeButNotItsTokens() {
		String masked = HttpSteps.maskJson(
				"{\"access_token\":\"eyJ.a.b\",\"refresh_token\" : \"r1\\\"x\",\"expires_in\":3600,\"scope\":\"read\"}");
		assertEquals("{\"access_token\":\"********\",\"refresh_token\" : \"********\",\"expires_in\":3600,"
				+ "\"scope\":\"read\"}", masked);
	}

	@Test
	void noBodyStaysNull() {
		assertNull(HttpSteps.maskForm(null));
		assertNull(HttpSteps.maskJson(null));
	}
}
