package org.vorpal.blade.framework.v3.security;

import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;

/// The caller as the container knows them: the authenticated user, and the
/// realm groups they hold.
///
/// This is the [SubjectAttributes] the OCCAS 8.3 path uses. The container's
/// OpenID Connect provider turns the identity provider's `groups` claim into
/// realm group principals before the request reaches any application code, so
/// by the time a rule is evaluated the caller's corporate group membership is
/// simply part of the authenticated subject. Nothing here parses a token,
/// fetches a key, or knows which identity provider the deployment uses.
///
/// ## Why not `isUserInRole`
///
/// The servlet API can only answer for roles declared in `web.xml` at build
/// time, and the whole point of an access policy is that the customer's group
/// names are not known when BLADE is built. Reading the subject is what lets an
/// operator write a rule about `cardiology-supervisors` without anyone
/// rebuilding a WAR.
///
/// ## Where the subject comes from
///
/// The caller supplies it, and the caller supplies the username with it. That
/// is a deliberate limit on this class rather than an omission: obtaining the
/// subject for the current thread means the container's own security API, which
/// is not on the framework's compile path today, and adding it is a change to
/// what every consumer repository has to install before it can build. Taking
/// the subject as an argument keeps that decision with the deployment and keeps
/// this class unit-testable with a hand-built `Subject`.
///
/// In a servlet the username is `HttpServletRequest.getUserPrincipal().getName()`.
///
/// ## Attributes are empty here, deliberately
///
/// The container consumes the ID token itself; the application never sees it,
/// so there are no arbitrary claims to expose. A rule that matches on
/// `${subject.<attribute>}` therefore never matches on this path, which fails
/// closed. Rules on this path match on groups, and on `${subject.name}` for the
/// common "their own calls" case. A deployment that can supply more builds its
/// own [SubjectAttributes] with [SubjectAttributes#of].
public final class RealmSubjectAttributes implements SubjectAttributes {

	private final String name;
	private final Set<String> groups;

	private RealmSubjectAttributes(String name, Set<String> groups) {
		this.name = name;
		this.groups = groups;
	}

	/// The caller described by an authenticated subject.
	///
	/// Every principal in the subject other than the named user is taken to be
	/// a group. That is the shape of a realm-authenticated subject: one
	/// principal for the user, one for each group they belong to. Reading it
	/// this way rather than by testing each principal's type keeps the framework
	/// off the container's security API, and costs only the username, which a
	/// servlet already has.
	///
	/// @param subject  the authenticated subject
	/// @param userName the caller's name, which names the one principal that is
	///                 not a group
	/// @return the caller, or null when either argument is missing — null
	///         denies with "no authenticated caller", which is the honest audit
	///         record for a request that reached an evaluation without one
	public static SubjectAttributes of(Subject subject, String userName) {
		if (subject == null || userName == null || userName.isEmpty()) {
			return null;
		}
		Set<String> found = new LinkedHashSet<>();
		for (Principal principal : subject.getPrincipals()) {
			String principalName = principal.getName();
			if (principalName != null && !userName.equals(principalName)) {
				found.add(principalName);
			}
		}
		return new RealmSubjectAttributes(userName, Collections.unmodifiableSet(found));
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public Set<String> groups() {
		return groups;
	}

	@Override
	public Map<String, String> attributes() {
		return Collections.emptyMap();
	}

	@Override
	public String toString() {
		return "RealmSubjectAttributes[" + name + " groups=" + groups + "]";
	}
}
