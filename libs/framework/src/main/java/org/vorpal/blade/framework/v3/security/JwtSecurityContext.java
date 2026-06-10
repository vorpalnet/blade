package org.vorpal.blade.framework.v3.security;

import java.security.Principal;

import javax.ws.rs.core.SecurityContext;

/// JAX-RS [SecurityContext] backed by a validated [JwtIdentity], installed by
/// [JwtAuthFilter] after a bearer token passes. Makes `getUserPrincipal()` and
/// `isUserInRole()` work for JWT callers exactly as they do for FORM/BASIC
/// ones, so resource code never has to know which front door the caller used.
public final class JwtSecurityContext implements SecurityContext {

	/// Authentication scheme name reported for JWT callers.
	public static final String BEARER_AUTH = "BEARER";

	private final JwtIdentity identity;
	private final boolean secure;

	public JwtSecurityContext(JwtIdentity identity, boolean secure) {
		this.identity = identity;
		this.secure = secure;
	}

	@Override
	public Principal getUserPrincipal() {
		return identity;
	}

	@Override
	public boolean isUserInRole(String role) {
		return identity.roles().contains(role);
	}

	@Override
	public boolean isSecure() {
		return secure;
	}

	@Override
	public String getAuthenticationScheme() {
		return BEARER_AUTH;
	}
}
