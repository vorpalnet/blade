package org.vorpal.blade.framework.v3.security;

import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/// Mints short-lived RS256 bearer tokens and publishes the public key as a JWKS.
///
/// The counterpart to [JwtValidator], and deliberately shaped like the IdP it
/// stands in for: it signs with a private key, it publishes the matching public
/// key in the standard JWKS document, and the consumer verifies offline against
/// that document. Nothing about the consumer's configuration says the issuer is
/// BLADE rather than Okta or Entra — the same [JwtAuthConfig] fields (`issuer`,
/// `jwksUri`, `audience`) describe either. Replacing this with a real IdP is
/// therefore a configuration change on the consumer and the deletion of this
/// object on the producer, with no code in between to rewrite.
///
/// ## Thread safety
///
/// Immutable after construction. [RSASSASigner] is safe for concurrent use, so
/// one issuer serves every request in the app.
///
/// ## The keypair
///
/// Generated here, held only in memory, never written down, never rotated. See
/// [JwtIssuerConfig] for why that is sufficient rather than a shortcut: the
/// tokens are short enough that none can outlive the process that signed them.
/// A restart mints a new `kid`, and the consumer's JWKS cache refetches when it
/// sees a key id it does not hold.
public final class JwtIssuer {

	/// RSA modulus size. 2048 is the floor RFC 7518 requires for RS256, and the
	/// size every JWKS consumer accepts without configuration.
	private static final int KEY_SIZE = 2048;

	private final JwtIssuerConfig config;
	private final RSAKey key;
	private final JWSSigner signer;
	private final String jwksJson;

	/// Build an issuer, generating its keypair. Costs one RSA keygen — do this
	/// once at startup, not per request.
	public JwtIssuer(JwtIssuerConfig config) throws JwtAuthException {
		if (config == null) {
			throw new JwtAuthException("issuer config is null");
		}
		this.config = config;
		try {
			this.key = new RSAKeyGenerator(KEY_SIZE)
					.keyID(UUID.randomUUID().toString())
					.keyUse(KeyUse.SIGNATURE)
					.algorithm(JWSAlgorithm.RS256)
					.generate();
			this.signer = new RSASSASigner(this.key);
			// toPublicJWK() strips every private field; this is the only form of
			// the key that ever leaves the process.
			this.jwksJson = new JWKSet(this.key.toPublicJWK()).toString();
		} catch (Exception e) {
			throw new JwtAuthException("could not generate a signing key", e);
		}
	}

	/// Mint a token for `subject` carrying `roles` and any `extraClaims`.
	///
	/// The claims an app adds are how a token says more than "this person is
	/// signed in" — the permission that was decided at mint time travels inside
	/// the signature, so the consumer enforces it without repeating the lookup.
	///
	/// @param subject     the authenticated principal; becomes `sub`
	/// @param roles       role names; written to the configured roles claim
	/// @param extraClaims app-specific string claims, or null
	public String mint(String subject, Collection<String> roles, Map<String, String> extraClaims)
			throws JwtAuthException {
		if (!config.isEnabled()) {
			throw new JwtAuthException("token issuing is disabled");
		}
		if (subject == null || subject.trim().isEmpty()) {
			throw new JwtAuthException("cannot mint a token with no subject");
		}
		long now = System.currentTimeMillis();

		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.subject(subject.trim())
				.issueTime(new Date(now))
				.expirationTime(new Date(now + (config.getTtlSeconds() * 1000L)))
				// A unique id per token, so a consumer that wants single-use
				// semantics has something to remember. Nothing here enforces it.
				.jwtID(UUID.randomUUID().toString());

		if (config.getIssuer() != null && !config.getIssuer().trim().isEmpty()) {
			claims.issuer(config.getIssuer().trim());
		}
		if (config.getAudience() != null && !config.getAudience().trim().isEmpty()) {
			claims.audience(config.getAudience().trim());
		}

		Set<String> roleValues = new LinkedHashSet<>();
		if (roles != null) {
			for (String role : roles) {
				if (role != null && !role.trim().isEmpty()) {
					roleValues.add(role.trim());
				}
			}
		}
		// Written as an array even when there is one role: both shapes are legal
		// and JwtValidator reads either, but an array never has to be re-parsed
		// on a delimiter, which is where the string form goes wrong.
		claims.claim(config.getRolesClaim(), new java.util.ArrayList<>(roleValues));

		if (extraClaims != null) {
			for (Map.Entry<String, String> entry : extraClaims.entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null) {
					claims.claim(entry.getKey(), entry.getValue());
				}
			}
		}

		try {
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256)
							.keyID(key.getKeyID())
							.type(JOSEObjectType.JWT)
							.build(),
					claims.build());
			jwt.sign(signer);
			return jwt.serialize();
		} catch (Exception e) {
			throw new JwtAuthException("could not sign token for '" + subject + "'", e);
		}
	}

	/// The public JWKS document, ready to serve at a `jwksUri`. Public keys, so
	/// this is safe to expose without authentication — and it has to be, since
	/// the consumer fetches it before it can authenticate anything.
	public String jwksJson() {
		return jwksJson;
	}

	/// The `kid` this issuer stamps on every token. Changes on every restart.
	public String keyId() {
		return key.getKeyID();
	}

	/// How long a freshly minted token lasts, in seconds — for a caller that
	/// wants to tell its client when to come back.
	public int ttlSeconds() {
		return config.getTtlSeconds();
	}
}
