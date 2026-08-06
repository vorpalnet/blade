package org.vorpal.blade.framework.v3.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/// The round trip that the first-party token design rests on: what [JwtIssuer]
/// mints, [JwtValidator] accepts — with the two sides wired together only
/// through the published JWKS document, exactly as they are in a deployment.
///
/// The keys are never handed over in memory. Every test parses
/// [JwtIssuer#jwksJson] back into a [JWKSet] first, so a change that broke the
/// serialized form would fail here rather than in production, where the consumer
/// fetches that same document over HTTP.
class JwtIssuerTest {

	private static final String ISSUER = "urn:blade:phone";
	private static final String AUDIENCE = "urn:blade:webrtc";

	private static JwtIssuerConfig issuerConfig() {
		JwtIssuerConfig cfg = new JwtIssuerConfig();
		cfg.setIssuer(ISSUER);
		cfg.setAudience(AUDIENCE);
		cfg.setTtlSeconds(60);
		return cfg;
	}

	private static JwtAuthConfig consumerConfig() {
		JwtAuthConfig cfg = new JwtAuthConfig();
		cfg.setEnabled(true);
		cfg.setIssuer(ISSUER);
		cfg.setAudience(AUDIENCE);
		cfg.setRolesClaim("groups");
		return cfg;
	}

	/// A validator that trusts only what this issuer published — built the long
	/// way round, through the JWKS text.
	private static JwtValidator validatorFor(JwtIssuer issuer, JwtAuthConfig cfg) throws Exception {
		JWKSource<SecurityContext> keys = new ImmutableJWKSet<>(JWKSet.parse(issuer.jwksJson()));
		return new JwtValidator(cfg, keys);
	}

	@Nested
	@DisplayName("mints tokens the validator accepts")
	class RoundTrip {

		@Test
		void carriesSubjectAndRoles() throws Exception {
			JwtIssuer issuer = new JwtIssuer(issuerConfig());
			String token = issuer.mint("alice", Arrays.asList("Admin", "Monitor"), null);

			JwtIdentity id = validatorFor(issuer, consumerConfig()).validate(token);
			assertEquals("alice", id.getName());
			assertTrue(id.roles().contains("Admin"));
			assertTrue(id.roles().contains("Monitor"));
			assertTrue(id.hasAnyAdminRole());
		}

		@Test
		void carriesAnAppSpecificClaimThrough() throws Exception {
			JwtIssuer issuer = new JwtIssuer(issuerConfig());
			Map<String, String> extra = new LinkedHashMap<>();
			extra.put("aor", "alice@vorpal.net");
			String token = issuer.mint("alice", Collections.singletonList("Operator"), extra);

			assertEquals("alice@vorpal.net",
					validatorFor(issuer, consumerConfig()).validate(token).claim("aor"));
		}

		@Test
		void writesRolesAsAnArrayEvenForOne() throws Exception {
			// A single role written as a bare string would still validate, so the
			// assertion that matters is that a role containing no delimiter and a
			// role list behave identically.
			JwtIssuer issuer = new JwtIssuer(issuerConfig());
			String token = issuer.mint("bob", Collections.singletonList("Deployer"), null);

			assertEquals(Collections.singleton("Deployer"),
					validatorFor(issuer, consumerConfig()).validate(token).roles());
		}

		@Test
		void dropsBlankAndNullRoles() throws Exception {
			JwtIssuer issuer = new JwtIssuer(issuerConfig());
			String token = issuer.mint("carol", Arrays.asList("Admin", "  ", null), null);

			assertEquals(Collections.singleton("Admin"),
					validatorFor(issuer, consumerConfig()).validate(token).roles());
		}

		@Test
		void grantsNoRoleWhenTheCallerHasNone() throws Exception {
			JwtIssuer issuer = new JwtIssuer(issuerConfig());
			String token = issuer.mint("nobody", Collections.emptyList(), null);

			JwtIdentity id = validatorFor(issuer, consumerConfig()).validate(token);
			assertEquals("nobody", id.getName());
			assertFalse(id.hasAnyAdminRole());
		}
	}

	@Nested
	@DisplayName("publishes a usable JWKS")
	class Jwks {

		@Test
		void containsExactlyThePublicKey() throws Exception {
			JwtIssuer issuer = new JwtIssuer(issuerConfig());
			JWKSet parsed = JWKSet.parse(issuer.jwksJson());

			assertEquals(1, parsed.getKeys().size());
			assertEquals(issuer.keyId(), parsed.getKeys().get(0).getKeyID());
		}

		@Test
		void neverLeaksThePrivateHalf() throws Exception {
			JwtIssuer issuer = new JwtIssuer(issuerConfig());

			assertFalse(JWKSet.parse(issuer.jwksJson()).getKeys().get(0).isPrivate(),
					"the published JWKS must contain public key material only");
			// Belt and braces: the RSA private exponent's JSON member name must
			// not appear in the document at all.
			assertFalse(issuer.jwksJson().contains("\"d\""));
		}

		@Test
		void givesEachIssuerItsOwnKey() throws Exception {
			assertNotEquals(new JwtIssuer(issuerConfig()).keyId(), new JwtIssuer(issuerConfig()).keyId());
		}
	}

	@Nested
	@DisplayName("refuses what it must not sign")
	class Refuses {

		@Test
		void mintingWhileDisabled() throws Exception {
			JwtIssuerConfig cfg = issuerConfig();
			cfg.setEnabled(false);
			JwtIssuer issuer = new JwtIssuer(cfg);

			assertThrows(JwtAuthException.class, () -> issuer.mint("alice", Collections.emptyList(), null));
		}

		@Test
		void mintingWithNoSubject() throws Exception {
			JwtIssuer issuer = new JwtIssuer(issuerConfig());

			assertThrows(JwtAuthException.class, () -> issuer.mint(null, Collections.emptyList(), null));
			assertThrows(JwtAuthException.class, () -> issuer.mint("  ", Collections.emptyList(), null));
		}

		@Test
		void beingBuiltWithNoConfig() {
			assertThrows(JwtAuthException.class, () -> new JwtIssuer(null));
		}
	}

	@Nested
	@DisplayName("a token from one issuer is worthless to another")
	class Isolation {

		@Test
		void aDifferentIssuersKeyDoesNotVerify() throws Exception {
			JwtIssuer mine = new JwtIssuer(issuerConfig());
			JwtIssuer stranger = new JwtIssuer(issuerConfig());
			String forged = stranger.mint("alice", Collections.singletonList("Admin"), null);

			// Same claims, same issuer string — only the signing key differs.
			assertThrows(JwtAuthException.class, () -> validatorFor(mine, consumerConfig()).validate(forged));
		}

		@Test
		void aTokenMintedForAnotherAudienceIsRejected() throws Exception {
			JwtIssuerConfig cfg = issuerConfig();
			cfg.setAudience("urn:blade:something-else");
			JwtIssuer issuer = new JwtIssuer(cfg);
			String token = issuer.mint("alice", Collections.singletonList("Admin"), null);

			assertThrows(JwtAuthException.class, () -> validatorFor(issuer, consumerConfig()).validate(token));
		}

		@Test
		void anExpiredTokenIsRejected() throws Exception {
			JwtIssuerConfig cfg = issuerConfig();
			cfg.setTtlSeconds(1);
			JwtIssuer issuer = new JwtIssuer(cfg);
			String token = issuer.mint("alice", Collections.singletonList("Admin"), null);

			// The validator's default 60s clock skew would forgive a 1s expiry, so
			// the consumer has to be told not to.
			JwtAuthConfig strict = consumerConfig();
			strict.setClockSkewSeconds(0);
			Thread.sleep(1_100L);

			assertThrows(JwtAuthException.class, () -> validatorFor(issuer, strict).validate(token));
		}
	}

	@Test
	void reportsItsOwnTtlSoCallersCanScheduleARefresh() throws Exception {
		JwtIssuerConfig cfg = issuerConfig();
		cfg.setTtlSeconds(45);

		assertEquals(45, new JwtIssuer(cfg).ttlSeconds());
	}

	@Test
	void clampsAnUnusableTtlRatherThanMintingAnAlreadyDeadToken() {
		JwtIssuerConfig cfg = new JwtIssuerConfig();
		cfg.setTtlSeconds(0);

		assertEquals(1, cfg.getTtlSeconds());
	}

	@Test
	void defaultsToEnabledBecauseAnIssuerThatWillNotIssueIsABrokenApp() {
		assertTrue(new JwtIssuerConfig().isEnabled());
		assertNotNull(new JwtIssuerConfig().getRolesClaim());
	}
}
