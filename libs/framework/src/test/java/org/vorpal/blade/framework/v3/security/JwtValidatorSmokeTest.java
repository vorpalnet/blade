package org.vorpal.blade.framework.v3.security;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/// Offline smoke test for [JwtValidator] — generates an RSA key, signs JWTs
/// locally, and feeds the public key in via an in-memory JWKS, so the whole
/// signature/issuer/audience/expiry/role-mapping path runs with no network and
/// no IdP. Same `main()` + pass/fail-counter style as `AuthenticationSmokeTest`
/// (the framework module has no JUnit).
///
/// Run: compile the test sources, then
/// `java -cp <framework classes + test classes + nimbus jars> \
///   org.vorpal.blade.framework.v3.security.JwtValidatorSmokeTest`
public final class JwtValidatorSmokeTest {

	private static int passed;
	private static int failed;

	private static final String ISSUER = "https://idp.example.com/";
	private static final String AUDIENCE = "blade-admin";

	private static RSAKey signingKey;       // private+public, signs tokens
	private static RSAKey strangerKey;       // an untrusted key, for the bad-signature case
	private static JWKSource<SecurityContext> trustedKeys; // only the signingKey's public half

	public static void main(String[] args) throws Exception {
		setUp();

		testValidTokenMapsGroupToAdmin();
		testDirectRoleNameWithoutMapping();
		testRolesClaimAsSpaceDelimitedString();
		testUsernameClaimOverride();
		testNonAdminGroupGetsNoRole();
		testWrongIssuerRejected();
		testWrongAudienceRejected();
		testExpiredTokenRejected();
		testForeignSignatureRejected();
		testGarbageTokenRejected();

		summary();
		System.exit(failed == 0 ? 0 : 1);
	}

	private static void setUp() throws Exception {
		signingKey = new RSAKeyGenerator(2048).keyID("blade-test-key").generate();
		strangerKey = new RSAKeyGenerator(2048).keyID("stranger-key").generate();
		trustedKeys = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
	}

	// ---- config builders ----

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

	// ---- token builders ----

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

	// ---- positive cases ----

	private static void testValidTokenMapsGroupToAdmin() throws Exception {
		String token = sign(signingKey, baseClaims()
				.claim("groups", Arrays.asList("blade-admins", "staff")).build());
		JwtIdentity id = new JwtValidator(baseConfig(), trustedKeys).validate(token);
		check("valid token: username", "alice".equals(id.getName()));
		check("valid token: Admin role mapped", id.roles().contains("Admin"));
		check("valid token: hasAnyAdminRole", id.hasAnyAdminRole());
		check("valid token: non-mapped group dropped", !id.roles().contains("staff"));
	}

	private static void testDirectRoleNameWithoutMapping() throws Exception {
		JwtAuthConfig cfg = baseConfig();
		cfg.setRoleMappings(new LinkedHashMap<>()); // no mappings: claim value used verbatim
		String token = sign(signingKey, baseClaims()
				.claim("groups", Arrays.asList("Operator")).build());
		JwtIdentity id = new JwtValidator(cfg, trustedKeys).validate(token);
		check("direct role name: Operator granted", id.roles().contains("Operator"));
	}

	private static void testRolesClaimAsSpaceDelimitedString() throws Exception {
		String token = sign(signingKey, baseClaims()
				.claim("groups", "staff blade-ops").build()); // single string, space-delimited
		JwtIdentity id = new JwtValidator(baseConfig(), trustedKeys).validate(token);
		check("string roles claim: Operator mapped", id.roles().contains("Operator"));
	}

	private static void testUsernameClaimOverride() throws Exception {
		JwtAuthConfig cfg = baseConfig();
		cfg.setUsernameClaim("preferred_username");
		String token = sign(signingKey, baseClaims()
				.claim("preferred_username", "alice@example.com")
				.claim("groups", Arrays.asList("blade-admins")).build());
		JwtIdentity id = new JwtValidator(cfg, trustedKeys).validate(token);
		check("username claim override", "alice@example.com".equals(id.getName()));
	}

	private static void testNonAdminGroupGetsNoRole() throws Exception {
		String token = sign(signingKey, baseClaims()
				.claim("groups", Arrays.asList("staff", "everyone")).build());
		JwtIdentity id = new JwtValidator(baseConfig(), trustedKeys).validate(token);
		check("non-admin groups: no admin role", !id.hasAnyAdminRole());
		check("non-admin groups: empty role set", id.roles().isEmpty());
	}

	// ---- negative cases (must throw) ----

	private static void testWrongIssuerRejected() throws Exception {
		String token = sign(signingKey, baseClaims()
				.issuer("https://evil.example.com/")
				.claim("groups", Arrays.asList("blade-admins")).build());
		checkRejected("wrong issuer rejected", baseConfig(), token);
	}

	private static void testWrongAudienceRejected() throws Exception {
		String token = sign(signingKey, baseClaims()
				.audience("some-other-app")
				.claim("groups", Arrays.asList("blade-admins")).build());
		checkRejected("wrong audience rejected", baseConfig(), token);
	}

	private static void testExpiredTokenRejected() throws Exception {
		// 5 minutes in the past — well beyond the 60s default clock skew.
		String token = sign(signingKey, baseClaims()
				.expirationTime(new Date(System.currentTimeMillis() - 300_000L))
				.claim("groups", Arrays.asList("blade-admins")).build());
		checkRejected("expired token rejected", baseConfig(), token);
	}

	private static void testForeignSignatureRejected() throws Exception {
		// Signed by a key not in the trusted JWKS.
		String token = sign(strangerKey, baseClaims()
				.claim("groups", Arrays.asList("blade-admins")).build());
		checkRejected("foreign signature rejected", baseConfig(), token);
	}

	private static void testGarbageTokenRejected() {
		checkRejected("garbage token rejected", baseConfig(), "not.a.jwt");
	}

	// ---- assertions ----

	private static void check(String name, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}

	private static void checkRejected(String name, JwtAuthConfig cfg, String token) {
		try {
			new JwtValidator(cfg, trustedKeys).validate(token);
			failed++;
			System.out.println("  FAIL  " + name + " (expected rejection, got acceptance)");
		} catch (JwtAuthException expected) {
			passed++;
			System.out.println("  PASS  " + name);
		}
	}

	private static void summary() {
		System.out.println();
		System.out.println("JwtValidatorSmokeTest: " + passed + " passed, " + failed + " failed");
	}
}
