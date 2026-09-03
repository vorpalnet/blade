package org.vorpal.blade.framework.v3.security;

import java.io.Serializable;
import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/// The authenticated caller derived from a validated bearer JWT: a username
/// (from the configured username claim, default `sub`), the set of BLADE admin
/// roles its group/role claim mapped to, the raw group values that claim
/// carried, and whatever app-specific claims the token carried beside them.
///
/// Implements [Principal] so it can be handed to a JAX-RS
/// [javax.ws.rs.core.SecurityContext], making `getUserPrincipal()` and
/// `isUserInRole()` behave the same for JWT callers as for FORM/BASIC ones.
public final class JwtIdentity implements Principal, Serializable {
	private static final long serialVersionUID = 1L;

	private final String name;
	private final Set<String> roles;
	private final Set<String> groups;
	private final Map<String, String> claims;

	public JwtIdentity(String name, Set<String> roles) {
		this(name, roles, null, null);
	}

	public JwtIdentity(String name, Set<String> roles, Map<String, String> claims) {
		this(name, roles, claims, null);
	}

	public JwtIdentity(String name, Set<String> roles, Map<String, String> claims, Set<String> groups) {
		this.name = name;
		this.roles = (roles == null) ? Collections.emptySet()
				: Collections.unmodifiableSet(new LinkedHashSet<>(roles));
		this.claims = (claims == null) ? Collections.emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(claims));
		this.groups = (groups == null) ? Collections.emptySet()
				: Collections.unmodifiableSet(new LinkedHashSet<>(groups));
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

	/// Every value the token's group/role claim carried, before mapping and
	/// without dropping anything. Never null.
	///
	/// [#roles] answers "may this caller into the admin tier", so it keeps only
	/// the four [AdminRole]s and discards the rest. An access rule asks a
	/// different question — which of the customer's groups is this person in —
	/// and the answer is mostly in what `roles()` throws away. Both come off the
	/// same claim; they are different projections of it, not the same one.
	///
	/// This is the bearer-token counterpart of the realm group principals
	/// [RealmSubjectAttributes] reads on the container path. A rule matching on
	/// a group name behaves the same whichever door the caller came through,
	/// which is the point.
	public Set<String> groups() {
		return groups;
	}

	/// A string claim carried by the token, or null if absent.
	///
	/// This is how a permission decided at mint time reaches the service that
	/// has to honor it: the issuing app resolves what the caller may do, writes
	/// it into a claim, and the signature makes it unforgeable in transit. The
	/// WebRTC gateway reads the address-of-record a browser is allowed to bind
	/// this way, so a signed-in user cannot claim someone else's address.
	///
	/// Only string-valued claims are exposed. Structured claims are deliberately
	/// out of scope — an authorization fact complex enough to need nesting is a
	/// design smell, and keeping the map flat keeps this class serializable.
	public String claim(String name) {
		return (name == null) ? null : claims.get(name);
	}

	/// Every string claim the token carried, standard ones included. Never null.
	public Map<String, String> claims() {
		return claims;
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
		return "JwtIdentity[name=" + name + ", roles=" + roles + ", groups=" + groups + "]";
	}
}
