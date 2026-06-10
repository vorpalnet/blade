package org.vorpal.blade.framework.v3.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/// The authenticated caller derived from a validated bearer JWT: a username
/// (from the configured username claim, default `sub`) and the set of BLADE
/// admin roles its group/role claim mapped to.
///
/// Implements [Principal] so it can be handed to a JAX-RS
/// [javax.ws.rs.core.SecurityContext], making `getUserPrincipal()` and
/// `isUserInRole()` behave the same for JWT callers as for FORM/BASIC ones.
public final class JwtIdentity implements Principal, Serializable {
	private static final long serialVersionUID = 1L;

	private final String name;
	private final Set<String> roles;

	public JwtIdentity(String name, Set<String> roles) {
		this.name = name;
		this.roles = (roles == null) ? Collections.emptySet()
				: Collections.unmodifiableSet(new LinkedHashSet<>(roles));
	}

	@Override
	public String getName() {
		return name;
	}

	/// The BLADE admin role names this caller holds (a subset of
	/// [AdminRole]). Never null.
	public Set<String> roles() {
		return roles;
	}

	/// True if this caller holds at least one of the four admin roles — the
	/// minimum bar for any admin app, matching the `<auth-constraint>` the
	/// container FORM path enforces.
	public boolean hasAnyAdminRole() {
		for (String role : roles) {
			if (AdminRole.isAdminRole(role)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString() {
		return "JwtIdentity[name=" + name + ", roles=" + roles + "]";
	}
}
