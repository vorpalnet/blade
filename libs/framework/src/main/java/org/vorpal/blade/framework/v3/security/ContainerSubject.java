package org.vorpal.blade.framework.v3.security;

import java.security.AccessController;
import java.lang.reflect.Method;

import javax.security.auth.Subject;

/// Find the authenticated [Subject] for the thread handling the current request.
///
/// ## Why this class exists
///
/// The obvious call, `Subject.getSubject(AccessController.getContext())`, returns
/// null here. It reads the subject off the JAAS access control context, which is
/// populated by `Subject.doAs`, and a servlet request is not dispatched inside a
/// `doAs`. The container authenticates the caller and associates the identity with
/// the thread by its own mechanism; the JAAS context is left empty.
///
/// That failure is silent and it fails *open* in the most dangerous way for an
/// access policy: no subject means no groups, so every rule that names a group
/// stops matching and the policy quietly grants nothing to anyone. A policy that
/// denies everything looks like a strict policy, not like a broken one. It cost a
/// live debugging session to find, which is why the lookup lives here in one place
/// instead of being written out at each call site.
///
/// ## Why reflection
///
/// The working call is `weblogic.security.Security.getCurrentSubject()`. It is a
/// documented, public API and the framework already compiles against WebLogic, so
/// importing it would compile. It is reached reflectively anyway so that this class
/// degrades off-container, which keeps the security classes unit testable with a
/// hand-built subject. The reflected name is resolved once.
///
/// ## Why three lookups and not one
///
/// The two fallbacks are reached reflectively for a second, unrelated reason:
/// BLADE compiles to Java 11 bytecode, and `Subject.current()` did not exist until
/// Java 18. Naming it directly will not compile at that release even though every
/// JVM this runs on has it.
///
/// They are tried newest first, because the old one is being withdrawn:
///
///  1. the container's own lookup, which is the one that answers in production;
///  2. `Subject.current()`, the modern JAAS answer, paired with `Subject.callAs`;
///  3. `Subject.getSubject(AccessController.getContext())`, the legacy answer.
///
/// The third is kept only for an older JVM. On a current one it does not merely
/// return null, it **throws `UnsupportedOperationException`**: it reads identity
/// from the access control context, that mechanism belongs to the Security Manager,
/// and the Security Manager is disallowed by default on modern Java. Measured on
/// JDK 25, which is a build JDK for this repo. So the line this class replaced was
/// broken twice over, and either break alone was enough to empty out every caller's
/// group set.
public final class ContainerSubject {

	/// `weblogic.security.Security.getCurrentSubject()`, or null if this is not
	/// running on WebLogic. Resolved once: the answer cannot change within a JVM.
	private static final Method GET_CURRENT_SUBJECT = resolveContainer();

	/// `Subject.current()`, absent before Java 18.
	private static final Method SUBJECT_CURRENT = resolveMethod(Subject.class, "current");

	private ContainerSubject() {
	}

	private static Method resolveContainer() {
		try {
			return resolveMethod(Class.forName("weblogic.security.Security"), "getCurrentSubject");
		} catch (Throwable off) {
			// Not on WebLogic (a unit test, or another container). The JAAS
			// fallbacks in current() are the answer there.
			return null;
		}
	}

	private static Method resolveMethod(Class<?> owner, String name) {
		try {
			return owner.getMethod(name);
		} catch (Throwable absent) {
			return null;
		}
	}

	/// Invoke a resolved static lookup, treating any failure as "no subject".
	private static Subject invoke(Method method) {
		if (method == null) {
			return null;
		}
		try {
			Object subject = method.invoke(null);
			return (subject instanceof Subject) ? (Subject) subject : null;
		} catch (Throwable ignore) {
			return null;
		}
	}

	/// The caller's subject, or null if the thread carries no authenticated
	/// identity. A null return is a legitimate answer, not an error: it is what an
	/// unauthenticated request looks like, and [AccessEvaluator] denies on it.
	///
	/// Never throws. This sits in front of an access check, and a lookup that threw
	/// would turn a clean deny into a 500 and lose the audit record with it.
	public static Subject current() {
		Subject subject = invoke(GET_CURRENT_SUBJECT);
		if (subject != null) {
			return subject;
		}
		subject = invoke(SUBJECT_CURRENT);
		if (subject != null) {
			return subject;
		}
		return legacySubject();
	}

	/// The pre-Java-18 lookup, isolated so its deprecation warning is confined to
	/// one method and its failure mode is documented where it happens.
	@SuppressWarnings("removal")
	private static Subject legacySubject() {
		try {
			// Throws UnsupportedOperationException on a JVM where the Security
			// Manager is disallowed, which is the default on current Java. Caught,
			// because "no subject" is the honest answer and this sits in front of
			// an access check that must not turn into a 500.
			return Subject.getSubject(AccessController.getContext());
		} catch (Throwable ignore) {
			return null;
		}
	}

	/// Which lookup answered, for a diagnostic line. Not part of a decision.
	public static String source() {
		if (invoke(GET_CURRENT_SUBJECT) != null) {
			return "container";
		}
		if (invoke(SUBJECT_CURRENT) != null) {
			return "jaas Subject.current()";
		}
		if (legacySubject() != null) {
			return "jaas getSubject (legacy)";
		}
		return (GET_CURRENT_SUBJECT == null) ? "none (not on WebLogic)" : "none";
	}
}
