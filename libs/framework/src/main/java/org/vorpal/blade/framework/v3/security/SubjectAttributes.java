package org.vorpal.blade.framework.v3.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/// Who the caller is, as far as an access rule is concerned: a name, the groups
/// they belong to, and any extra attributes the deployment can supply.
///
/// ## Why groups, and not token claims
///
/// On OCCAS 8.3 the container's OpenID Connect provider consumes the ID token
/// itself and turns the token's `groups` claim into realm principals. The
/// application never sees the token, so an access rule cannot match on an
/// arbitrary claim — the one attribute guaranteed to survive the trip is group
/// membership. That is not a limitation worth working around: enterprise
/// identity providers already model team, department and job function as
/// groups, which is exactly the shape a scope predicate wants.
///
/// [#attributes()] exists for deployments that *can* supply more — a directory
/// lookup, or the bearer-token path where [JwtIdentity#claims()] is in hand —
/// and is empty otherwise. A rule that matches on an attribute nobody supplies
/// simply never matches, which fails closed.
///
/// ## Why this is an interface
///
/// Reading full group membership means asking the container, and the container
/// is not available in a unit test. Everything above [AccessEvaluator] talks to
/// this interface, so the evaluator and its tests never import a WebLogic type
/// and never need a running domain. [#of] is the fake; the container-backed
/// implementation lives with the application that installs it.
public interface SubjectAttributes {

	/// The authenticated caller's name, for the audit record. Never null in
	/// practice; an unauthenticated request should not reach an evaluation at
	/// all.
	String name();

	/// Every group the caller belongs to, as the identity provider named them.
	/// Never null.
	Set<String> groups();

	/// Extra caller attributes a rule may match on, keyed by name. Never null,
	/// and empty on the container path — see the class note.
	Map<String, String> attributes();

	/// True if the caller is in `group`. Case-sensitive, matching how the realm
	/// compares group names.
	default boolean inGroup(String group) {
		return group != null && groups().contains(group);
	}

	/// One attribute, or null.
	default String attribute(String name) {
		return (name == null) ? null : attributes().get(name);
	}

	/// A fixed set of attributes, for tests and for callers that already hold
	/// the facts. The returned value copies its inputs and is immutable.
	static SubjectAttributes of(String name, Set<String> groups, Map<String, String> attributes) {
		Set<String> g = Collections
				.unmodifiableSet(new LinkedHashSet<>((groups == null) ? Collections.emptySet() : groups));
		Map<String, String> a = Collections
				.unmodifiableMap(new LinkedHashMap<>((attributes == null) ? Collections.emptyMap() : attributes));
		return new SubjectAttributes() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public Set<String> groups() {
				return g;
			}

			@Override
			public Map<String, String> attributes() {
				return a;
			}

			@Override
			public String toString() {
				return "SubjectAttributes[" + name + " groups=" + g + "]";
			}
		};
	}

	/// A caller with a name and groups and nothing else — the container shape.
	static SubjectAttributes of(String name, Set<String> groups) {
		return of(name, groups, Collections.emptyMap());
	}

	/// The caller described by a validated bearer token.
	///
	/// Takes [JwtIdentity#groups] — every value the token's group claim
	/// carried — and not [JwtIdentity#roles], which keeps only the four
	/// [AdminRole]s. A rule matches on the customer's group names, so it must
	/// see the claim as the identity provider wrote it. Using `roles()` here
	/// would silently mean that only a caller holding an admin role could match
	/// any rule at all, which is the exact coupling between the two vocabularies
	/// that [AccessEvaluator] exists to prevent.
	///
	/// Unlike the container path, the token's string claims are available, so a
	/// rule may match on `${subject.<claim>}` here.
	static SubjectAttributes of(JwtIdentity identity) {
		return (identity == null) ? null
				: of(identity.getName(), identity.groups(), identity.claims());
	}
}
