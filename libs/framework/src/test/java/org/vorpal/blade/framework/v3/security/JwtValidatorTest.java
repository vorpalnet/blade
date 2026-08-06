package org.vorpal.blade.framework.v3.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/// Offline coverage for [JwtValidator]: an RSA key is generated here, tokens are
/// signed here, and the public half is fed in through an in-memory JWKS — so the
/// whole signature/issuer/audience/expiry/role-mapping path runs with no network
/// and no IdP.
///
/// This replaces a `main()`-driven pass/fail driver of the same name. Surefire
/// never ran it, so the JWT path had no coverage in the build at all while
/// SECURITY.md listed it as verified.
class JwtValidatorTest {

	private static final String ISSUER = "https://idp.example.com/";
	private static final String AUDIENCE = "blade-admin";

	/// Signs the tokens under test; its public half is the only trusted key.
	private static RSAKey signingKey;
	/// A key the validator has never heard of, for the bad-signature case.
	private static RSAKey strangerKey;
	private static JWKSource<SecurityContext> trustedKeys;

	@BeforeAll
	static void generateKeys() throws Exception {
		signingKey = new RSAKeyGenerator(2048).keyID("blade-test-key").generate();
		strangerKey = new RSAKeyGenerator(2048).keyID("stranger-key").generate();
		trustedKeys = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
	}

	private static JwtAuthConfig baseConfig() {
		JwtAuthConfig cfg = new JwtAuthConfig();
		cfg.setEnabled(true);
		cfg.setIssuer(ISSUER);
		cfg.setAudience(AUDIENCE);
		cfg.setRolesClaim("groups");
		cfg.setUsernameClaim("sub");
		Map<String, String> mappings = new LinkedHashMap<>();
		mappings.put("blade-admins", "Admin");
		mappings.put("blade-ops", "Operator");
		cfg.setRoleMappings(mappings);
		return cfg;
	}

	private static JWTClaimsSet.Builder baseClaims() {
		return new JWTClaimsSet.Builder()
				.subject("alice")
				.issuer(ISSUER)
				.audience(AUDIENCE)
				.expirationTime(new Date(System.currentTimeMillis() + 3_600_000L));
	}

	private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
		JWSSigner signer = new RSASSASigner(key);
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
				claims);
		jwt.sign(signer);
		return jwt.serialize();
	}

	@Nested
	@DisplayName("accepts a well-formed token")
	class Accepts {

		@Test
		void mapsConfiguredGroupToAdminRole() throws Exception {
			String token = sign(signingKey, baseClaims()
					.claim("groups", Arrays.asList("blade-admins", "staff")).build());
			JwtIdentity id = new JwtValidator(baseConfig(), trustedKeys).validate(token);

			assertEquals("alice", id.getName());
			assertTrue(id.roles().contains("Admin"));
			assertTrue(id.hasAnyAdminRole());
			assertFalse(id.roles().contains("staff"), "an unmapped group must not become a role");
		}

		@Test
		void usesClaimValueVerbatimWhenNoMappingConfigured() throws Exception {
			JwtAuthConfig cfg = baseConfig();
			cfg.setRoleMappings(new LinkedHashMap<>());
			String token = sign(signingKey, baseClaims()
					.claim("groups", Arrays.asList("Operator")).build());

			assertTrue(new JwtValidator(cfg, trustedKeys).validate(token).roles().contains("Operator"));
		}

		@Test
		void readsRolesFromADelimitedString() throws Exception {
			String token = sign(signingKey, baseClaims()
					.claim("groups", "staff blade-ops").build());

			assertTrue(new JwtValidator(baseConfig(), trustedKeys).validate(token)
					.roles().contains("Operator"));
		}

		@Test
		void honorsTheConfiguredUsernameClaim() throws Exception {
			JwtAuthConfig cfg = baseConfig();
			cfg.setUsernameClaim("preferred_username");
			String token = sign(signingKey, baseClaims()
					.claim("preferred_username", "alice@example.com")
					.claim("groups", Arrays.asList("blade-admins")).build());

			assertEquals("alice@example.com", new JwtValidator(cfg, trustedKeys).validate(token).getName());
		}

		@Test
		void grantsNothingForGroupsThatAreNotAdminRoles() throws Exception {
			String token = sign(signingKey, baseClaims()
					.claim("groups", Arrays.asList("staff", "everyone")).build());
			JwtIdentity id = new JwtValidator(baseConfig(), trustedKeys).validate(token);

			assertFalse(id.hasAnyAdminRole());
			assertTrue(id.roles().isEmpty());
		}
	}

	@Nested
	@DisplayName("exposes app-specific string claims")
	class Claims {

		@Test
		void surfacesAStringClaimTheTokenCarried() throws Exception {
			String token = sign(signingKey, baseClaims()
					.claim("groups", Arrays.asList("blade-admins"))
					.claim("aor", "alice@vorpal.net").build());

			assertEquals("alice@vorpal.net",
					new JwtValidator(baseConfig(), trustedKeys).validate(token).claim("aor"));
		}

		@Test
		void skipsNonStringClaimsRatherThanStringifyingThem() throws Exception {
			String token = sign(signingKey, baseClaims()
					.claim("groups", Arrays.asList("blade-admins")).build());
			JwtIdentity id = new JwtValidator(baseConfig(), trustedKeys).validate(token);

			assertNull(id.claim("groups"), "the list-valued roles claim must not appear as a string");
			assertEquals("alice", id.claim("sub"), "string claims still come through");
		}

		@Test
		void returnsNullForAClaimTheTokenDoesNotCarry() throws Exception {
			String token = sign(signingKey, baseClaims()
					.claim("groups", Arrays.asList("blade-admins")).build());
			JwtIdentity id = new JwtValidator(baseConfig(), trustedKeys).validate(token);

			assertNull(id.claim("aor"));
			assertNull(id.claim(null));
		}
	}

	@Nested
	@DisplayName("rejects a token it cannot vouch for")
	class Rejects {

		private void assertRejected(JwtAuthConfig cfg, String token) {
			assertThrows(JwtAuthException.class, () -> new JwtValidator(cfg, trustedKeys).validate(token));
		}

		@Test
		void issuedBySomeoneElse() throws Exception {
			assertRejected(baseConfig(), sign(signingKey, baseClaims()
					.issuer("https://evil.example.com/")
					.claim("groups", Arrays.asList("blade-admins")).build()));
		}

		@Test
		void mintedForADifferentAudience() throws Exception {
			assertRejected(baseConfig(), sign(signingKey, baseClaims()
					.audience("some-other-app")
					.claim("groups", Arrays.asList("blade-admins")).build()));
		}

		@Test
		void expired() throws Exception {
			// Five minutes past, well beyond the 60s default clock skew.
			assertRejected(baseConfig(), sign(signingKey, baseClaims()
					.expirationTime(new Date(System.currentTimeMillis() - 300_000L))
					.claim("groups", Arrays.asList("blade-admins")).build()));
		}

		@Test
		void signedByAKeyOutsideTheJwks() throws Exception {
			assertRejected(baseConfig(), sign(strangerKey, baseClaims()
					.claim("groups", Arrays.asList("blade-admins")).build()));
		}

		@Test
		void notAJwtAtAll() {
			assertRejected(baseConfig(), "not.a.jwt");
		}

		@Test
		void empty() {
			assertRejected(baseConfig(), "");
			assertRejected(baseConfig(), null);
		}
	}
}
