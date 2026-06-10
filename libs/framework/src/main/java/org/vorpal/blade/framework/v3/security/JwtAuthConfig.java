package org.vorpal.blade.framework.v3.security;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Configuration for inbound bearer-JWT authentication of admin requests.
///
/// This is the deployment-editable description of the enterprise identity
/// provider (IdP) BLADE trusts to assert who a caller is. OCCAS is **not** the
/// source of identity — passwords, groups and roles live in the corporate IdP;
/// BLADE only validates the IdP's signature and maps its group/role claim onto
/// the four [AdminRole]s.
///
/// Edited through the Configurator like any other BLADE config (it is a
/// section of the `security` admin app's settings), so every field carries a
/// `@JsonPropertyDescription` for the dynamic form. Read at request time by
/// [JwtAuthFilter]; consumed by [JwtValidator].
///
/// Leaving [#isEnabled()] false (the default) makes the JWT path a no-op — the
/// filter falls through to the container FORM/BASIC login, so this can be
/// shipped dormant and switched on per deployment.
public class JwtAuthConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	private boolean enabled = false;
	private String issuer;
	private String jwksUri;
	private String audience;
	private String algorithm = "RS256";
	private String usernameClaim = "sub";
	private String rolesClaim = "groups";
	private Map<String, String> roleMappings = new LinkedHashMap<>();
	private int clockSkewSeconds = 60;

	@JsonPropertyDescription("Master switch. When false (default) the JWT path is a no-op and admin apps use the container FORM/BASIC login. Turn on per deployment once the IdP fields below are set.")
	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@JsonPropertyDescription("Expected token issuer (the IdP 'iss' claim), matched exactly. e.g. https://login.example.com/")
	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	@JsonPropertyDescription("HTTPS URL of the IdP's JWKS (public signing keys). BLADE fetches and caches these to verify token signatures. e.g. https://login.example.com/.well-known/jwks.json")
	public String getJwksUri() {
		return jwksUri;
	}

	public void setJwksUri(String jwksUri) {
		this.jwksUri = jwksUri;
	}

	@JsonPropertyDescription("Optional expected audience (the 'aud' claim). When set, tokens not issued for this audience are rejected. Leave blank to skip the audience check.")
	public String getAudience() {
		return audience;
	}

	public void setAudience(String audience) {
		this.audience = audience;
	}

	@JsonPropertyDescription("Expected JWS signing algorithm. Default RS256 (RSA). Must match what the IdP signs with.")
	public String getAlgorithm() {
		return algorithm;
	}

	public void setAlgorithm(String algorithm) {
		this.algorithm = algorithm;
	}

	@JsonPropertyDescription("Claim that carries the caller's username/principal. Default 'sub'. Some IdPs prefer 'preferred_username' or 'email'.")
	public String getUsernameClaim() {
		return usernameClaim;
	}

	public void setUsernameClaim(String usernameClaim) {
		this.usernameClaim = usernameClaim;
	}

	@JsonPropertyDescription("Claim that carries the caller's groups/roles. Default 'groups'. May be a single string or a list of strings in the token.")
	public String getRolesClaim() {
		return rolesClaim;
	}

	public void setRolesClaim(String rolesClaim) {
		this.rolesClaim = rolesClaim;
	}

	@JsonPropertyDescription("Maps IdP group/role values to BLADE roles (Admin, Operator, Deployer, Monitor). e.g. 'blade-admins' -> 'Admin'. A claim value that is already a BLADE role name needs no entry; values that map (or already equal) a non-admin name grant no access.")
	public Map<String, String> getRoleMappings() {
		return roleMappings;
	}

	public void setRoleMappings(Map<String, String> roleMappings) {
		this.roleMappings = (roleMappings == null) ? new LinkedHashMap<>() : roleMappings;
	}

	@JsonPropertyDescription("Allowed clock skew, in seconds, when checking token expiry/not-before. Default 60.")
	public int getClockSkewSeconds() {
		return clockSkewSeconds;
	}

	public void setClockSkewSeconds(int clockSkewSeconds) {
		this.clockSkewSeconds = clockSkewSeconds;
	}
}
