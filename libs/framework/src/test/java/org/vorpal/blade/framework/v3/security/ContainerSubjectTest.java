package org.vorpal.blade.framework.v3.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.security.auth.Subject;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Tests for [ContainerSubject].
///
/// These run off-container, so they exercise the JAAS lookups rather than the
/// container one. That is the half a test can reach, and it is worth reaching: it
/// is what keeps every other security test able to build a caller by hand. The
/// container half was verified on the rig, where the diagnostic reported
/// `via container` and the caller's realm group appeared in the policy decision.
///
/// The behaviour pinned down here is the one whose absence caused the bug: a lookup
/// that finds nothing returns null rather than throwing, because a throw in front
/// of an access check turns a clean deny into a 500 and loses the audit record with
/// it.
///
/// `Subject.callAs` is invoked reflectively for the same reason [ContainerSubject]
/// reflects on `Subject.current()`: this module compiles to Java 11 bytecode and
/// neither method existed before Java 18.
class ContainerSubjectTest {

	/// `Subject.callAs(Subject, Callable)`, absent before Java 18.
	private static final Method CALL_AS = callAs();

	private static Method callAs() {
		try {
			return Subject.class.getMethod("callAs", Subject.class, Callable.class);
		} catch (Throwable absent) {
			return null;
		}
	}

	/// Run `work` with `subject` established on the thread, the modern way.
	private static <T> T as(Subject subject, Callable<T> work) throws Exception {
		Assumptions.assumeTrue(CALL_AS != null, "Subject.callAs requires Java 18+");
		@SuppressWarnings("unchecked")
		T result = (T) CALL_AS.invoke(null, subject, work);
		return result;
	}

	private static Subject subjectWith(String... principalNames) {
		Set<Principal> principals = new HashSet<>();
		for (String name : principalNames) {
			principals.add(new NamedPrincipal(name));
		}
		return new Subject(true, principals, new HashSet<>(), new HashSet<>());
	}

	@Test
	@DisplayName("returns null when the thread carries no identity")
	void nullWhenNoSubject() {
		// A plain test thread is unauthenticated. Null is the right answer, and
		// AccessEvaluator denies on it.
		assertNull(ContainerSubject.current());
	}

	@Test
	@DisplayName("finds the subject established on the thread")
	void findsEstablishedSubject() throws Exception {
		Subject subject = subjectWith("alice", "CLINICAL-SUPERVISORS");

		Subject found = as(subject, ContainerSubject::current);

		assertNotNull(found, "an established subject must be visible to the lookup");
		assertEquals(subject, found);
	}

	@Test
	@DisplayName("the subject it finds yields the caller's groups")
	void groupsSurviveTheLookup() throws Exception {
		// The end-to-end shape, and the exact thing that broke: when the lookup
		// returned null the group set came back empty, every rule naming a group
		// stopped matching, and the policy granted nothing to anybody while
		// looking like a strict policy.
		Subject subject = subjectWith("alice", "CLINICAL-SUPERVISORS", "AUDIT");

		SubjectAttributes caller = as(subject,
				() -> RealmSubjectAttributes.of(ContainerSubject.current(), "alice"));

		assertNotNull(caller);
		assertEquals("alice", caller.name());
		assertEquals(new HashSet<>(Arrays.asList("CLINICAL-SUPERVISORS", "AUDIT")), caller.groups());
	}

	@Test
	@DisplayName("an empty subject yields a caller with no groups, not a failure")
	void emptySubjectIsNotAnError() throws Exception {
		SubjectAttributes caller = as(subjectWith("bob"),
				() -> RealmSubjectAttributes.of(ContainerSubject.current(), "bob"));

		assertNotNull(caller);
		assertTrue(caller.groups().isEmpty());
	}

	@Test
	@DisplayName("never throws, even where the legacy lookup does")
	void neverThrows() {
		// On a JVM where the Security Manager is disallowed the legacy JAAS call
		// throws UnsupportedOperationException. Sitting in front of every access
		// check, including unauthenticated ones, this has to be total.
		for (int i = 0; i < 3; i++) {
			ContainerSubject.current();
		}
		assertNull(ContainerSubject.current());
	}

	@Test
	@DisplayName("reports which lookup answered, without deciding anything")
	void reportsItsSource() throws Exception {
		assertTrue(ContainerSubject.source().startsWith("none"),
				"with no subject on the thread, no lookup answers");

		String withSubject = as(subjectWith("alice"), ContainerSubject::source);
		assertTrue(withSubject.startsWith("jaas"),
				"off-container a JAAS lookup should answer, was: " + withSubject);
	}

	private static final class NamedPrincipal implements Principal {
		private final String name;

		NamedPrincipal(String name) {
			this.name = name;
		}

		@Override
		public String getName() {
			return name;
		}
	}
}
