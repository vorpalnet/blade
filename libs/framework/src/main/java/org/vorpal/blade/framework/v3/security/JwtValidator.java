package org.vorpal.blade.framework.v3.security;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/// Validates a bearer JWT against a configured IdP and maps its group/role
/// claim onto BLADE [AdminRole]s. Pure, container-free, and stateless per
/// request — built once from a [JwtAuthConfig], then reused across requests.
///
/// Signature verification keys come from a [JWKSource]:
/// - production builds a network-backed, caching source from the config's
///   JWKS URI ([#forConfig(JwtAuthConfig)]);
/// - tests pass an in-memory `ImmutableJWKSet` to the constructor, so the
///   whole validation path runs offline with a locally-signed token
///   (see `JwtValidatorSmokeTest`).
///
/// What it checks: the JWS signature (against the configured algorithm and the
/// JWKS), the issuer (exact match), the audience (when configured), and expiry
/// (with the configured clock skew). What it does NOT do: mint tokens, talk to
/// the IdP for anything but the JWKS, or establish a session — identity is
/// asserted by the IdP and trusted here.
public final class JwtValidator {

	private final JwtAuthConfig config;
	private final ConfigurableJWTProcessor<SecurityContext> processor;

	/// Build a validator from config and an explicit key source. Tests use
	/// this with an in-memory JWKS; production goes through
	/// [#forConfig(JwtAuthConfig)].
	public JwtValidator(JwtAuthConfig config, JWKSource<SecurityContext> jwkSource) {
		this.config = config;

		JWSAlgorithm alg = JWSAlgorithm.parse(
				(config.getAlgorithm() == null || config.getAlgorithm().isEmpty())
						? "RS256" : config.getAlgorithm());

		DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
		JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(alg, jwkSource);
		p.setJWSKeySelector(keySelector);

		String requiredAudience = null;
		if (config.getAudience() != null && !config.getAudience().isEmpty()) {
			requiredAudience = config.getAudience();
		}

		JWTClaimsSet.Builder exactMatch = new JWTClaimsSet.Builder();
		if (config.getIssuer() != null && !config.getIssuer().isEmpty()) {
			exactMatch.issuer(config.getIssuer());
		}

		Set<String> requiredClaims = new HashSet<>(Arrays.asList("exp"));
		DefaultJWTClaimsVerifier<SecurityContext> verifier =
				new DefaultJWTClaimsVerifier<SecurityContext>(requiredAudience, exactMatch.build(), requiredClaims);
		verifier.setMaxClockSkew(Math.max(0, config.getClockSkewSeconds()));
		p.setJWTClaimsSetVerifier(verifier);

		this.processor = p;
	}

	/// Build a production validator whose keys are fetched (and cached) from
	/// the config's JWKS URI.
	public static JwtValidator forConfig(JwtAuthConfig config) throws JwtAuthException {
		if (config.getJwksUri() == null || config.getJwksUri().isEmpty()) {
			throw new JwtAuthException("jwksUri is not configured");
		}
		try {
			URL url = new URL(config.getJwksUri());
			JWKSource<SecurityContext> source = JWKSourceBuilder.<SecurityContext>create(url).build();
			return new JwtValidator(config, source);
		} catch (Exception e) {
			throw new JwtAuthException("cannot build JWKS source from " + config.getJwksUri(), e);
		}
	}

	/// Verify the token and resolve the caller. Throws [JwtAuthException] on
	/// any signature/issuer/audience/expiry failure — the filter turns that
	/// into a `401`.
	public JwtIdentity validate(String token) throws JwtAuthException {
		if (token == null || token.isEmpty()) {
			throw new JwtAuthException("empty bearer token");
		}
		JWTClaimsSet claims;
		try {
			claims = processor.process(token, null);
		} catch (Exception e) {
			throw new JwtAuthException("JWT validation failed: " + e.getMessage(), e);
		}
		return new JwtIdentity(resolveUsername(claims), mapRoles(claims));
	}

	private String resolveUsername(JWTClaimsSet claims) {
		String claimName = (config.getUsernameClaim() == null || config.getUsernameClaim().isEmpty())
				? "sub" : config.getUsernameClaim();
		Object value = claims.getClaim(claimName);
		if (value != null) {
			return value.toString();
		}
		return claims.getSubject();
	}

	/// Map the raw group/role claim values to the BLADE admin roles the caller
	/// holds. A configured mapping wins; otherwise the raw value is used
	/// directly (so a token can carry `Admin`/`Operator`/… verbatim). Values
	/// that resolve to a non-admin name are dropped.
	private Set<String> mapRoles(JWTClaimsSet claims) {
		Map<String, String> mappings = config.getRoleMappings();
		Set<String> roles = new LinkedHashSet<>();
		for (String raw : extractRoleValues(claims)) {
			String mapped = (mappings != null && mappings.containsKey(raw)) ? mappings.get(raw) : raw;
			if (AdminRole.isAdminRole(mapped)) {
				roles.add(mapped);
			}
		}
		return roles;
	}

	/// Read the roles claim, accepting either a single (possibly space- or
	/// comma-delimited) string or a JSON array of strings — both are common
	/// across IdPs.
	private List<String> extractRoleValues(JWTClaimsSet claims) {
		String claimName = (config.getRolesClaim() == null || config.getRolesClaim().isEmpty())
				? "groups" : config.getRolesClaim();
		Object raw = claims.getClaim(claimName);
		List<String> values = new ArrayList<>();
		if (raw == null) {
			return values;
		}
		if (raw instanceof String) {
			for (String part : ((String) raw).split("[\\s,]+")) {
				if (!part.isEmpty()) {
					values.add(part);
				}
			}
		} else if (raw instanceof Collection) {
			for (Object item : (Collection<?>) raw) {
				if (item != null) {
					values.add(item.toString());
				}
			}
		}
		return values;
	}
}
