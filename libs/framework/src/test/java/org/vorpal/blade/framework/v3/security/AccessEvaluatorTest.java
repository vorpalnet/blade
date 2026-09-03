package org.vorpal.blade.framework.v3.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/// Offline coverage for the access-control layer: [AccessEvaluator],
/// [AccessRule], [DataPermission] and the two [SubjectAttributes] adapters.
///
/// No container, no realm, no identity provider. [SubjectAttributes] is the seam
/// that makes that possible — the evaluator never asks the container anything,
/// so the rules can be exercised as pure functions.
///
/// The cases that matter are the ones where a caller who is plainly legitimate
/// is still refused: a platform administrator who holds no data permission, a
/// supervisor reaching for another team's call, a rule that depends on an
/// attribute the deployment does not supply. Those are the behaviors an access
/// review asks about, and each of them is a bug waiting to happen in the other
/// direction.
class AccessEvaluatorTest {

	private static SubjectAttributes caller(String name, String... groups) {
		return SubjectAttributes.of(name, new LinkedHashSet<>(Arrays.asList(groups)));
	}

	private static Map<String, String> record(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	private static AccessRule rule(String name, Set<String> groups, Map<String, String> match, String... permits) {
		return new AccessRule(name, (groups == null) ? null : new LinkedList<>(groups),
				(match == null) ? null : new LinkedHashMap<>(match), Arrays.asList(permits));
	}

	private static AccessEvaluator evaluator(AccessRule... rules) {
		AccessPolicy policy = new AccessPolicy();
		policy.getRules().addAll(Arrays.asList(rules));
		return new AccessEvaluator(policy);
	}

	@Nested
	@DisplayName("denies by default")
	class DeniesByDefault {

		@Test
		@DisplayName("a null policy denies, and says the policy is missing rather than that no rule matched")
		void nullPolicyDenies() {
			AccessDecision decision = new AccessEvaluator(null).evaluate(caller("alice"), DataPermission.PLAY, null);
			assertFalse(decision.isAllowed());
			assertEquals("no access policy is loaded", decision.getReason());
		}

		@Test
		@DisplayName("an empty policy denies")
		void emptyPolicyDenies() {
			AccessDecision decision = evaluator().evaluate(caller("alice"), DataPermission.PLAY, null);
			assertFalse(decision.isAllowed());
			assertEquals("the access policy has no rules", decision.getReason());
		}

		@Test
		@DisplayName("a caller nobody wrote a rule about is refused")
		void unmatchedCallerDenied() {
			AccessEvaluator evaluator = evaluator(
					rule("reviewers", Collections.singleton("qa-reviewers"), null, "phi:play"));
			assertFalse(evaluator.evaluate(caller("bob", "billing"), DataPermission.PLAY, null).isAllowed());
		}

		@Test
		@DisplayName("no authenticated caller is a distinct reason from no matching rule")
		void nullSubjectDenied() {
			AccessDecision decision = evaluator(rule("everyone", null, null, "phi:play"))
					.evaluate(null, DataPermission.PLAY, null);
			assertFalse(decision.isAllowed());
			assertEquals("no authenticated caller", decision.getReason());
		}
	}

	@Nested
	@DisplayName("the permission ladder")
	class Ladder {

		private final AccessEvaluator evaluator = evaluator(
				rule("reviewers may listen", Collections.singleton("qa-reviewers"), null, "phi:list", "phi:play"));

		@Test
		@DisplayName("grants the rungs the rule names")
		void grantsNamedRungs() {
			SubjectAttributes alice = caller("alice", "qa-reviewers");
			assertTrue(evaluator.evaluate(alice, DataPermission.LIST, null).isAllowed());
			assertTrue(evaluator.evaluate(alice, DataPermission.PLAY, null).isAllowed());
		}

		@Test
		@DisplayName("play does not imply export: hearing a call is not taking a copy of it")
		void playDoesNotImplyExport() {
			assertFalse(evaluator.evaluate(caller("alice", "qa-reviewers"), DataPermission.EXPORT, null).isAllowed());
		}

		@Test
		@DisplayName("play does not imply transcript, or unredact")
		void playImpliesNothingElse() {
			SubjectAttributes alice = caller("alice", "qa-reviewers");
			assertFalse(evaluator.evaluate(alice, DataPermission.TRANSCRIPT, null).isAllowed());
			assertFalse(evaluator.evaluate(alice, DataPermission.UNREDACT, null).isAllowed());
		}

		@Test
		@DisplayName("a permit entry that is not a permission grants nothing, and is reported")
		void unknownPermitEntryGrantsNothing() {
			AccessPolicy policy = new AccessPolicy();
			policy.getRules().add(rule("typo", null, null, "phi:play", "phi:playback"));
			AccessEvaluator evaluator = new AccessEvaluator(policy);
			assertTrue(evaluator.evaluate(caller("alice"), DataPermission.PLAY, null).isAllowed());
			assertEquals(Collections.singleton("phi:playback"), policy.unknownPermissions());
		}
	}

	@Nested
	@DisplayName("platform roles grant no data permission")
	class PlaneSeparation {

		@Test
		@DisplayName("the two vocabularies do not overlap in either direction")
		void vocabulariesAreDisjoint() {
			for (AdminRole role : AdminRole.values()) {
				assertNull(DataPermission.fromName(role.roleName()),
						role.roleName() + " must not resolve to a data permission");
			}
			for (DataPermission permission : DataPermission.values()) {
				assertNull(AdminRole.fromName(permission.permissionName()),
						permission.permissionName() + " must not resolve to an admin role");
			}
		}

		@Test
		@DisplayName("an Admin with no rule about them hears nothing")
		void adminHearsNothing() {
			AccessEvaluator evaluator = evaluator(
					rule("reviewers", Collections.singleton("qa-reviewers"), null, "phi:play"));
			assertFalse(evaluator.evaluate(caller("root", "Admin", "Operator", "Deployer", "Monitor"),
					DataPermission.PLAY, null).isAllowed());
		}

		@Test
		@DisplayName("a Monitor may still be granted content by a rule that names the group, in the open")
		void adminMayBeGrantedExplicitly() {
			AccessEvaluator evaluator = evaluator(
					rule("operators may audit", Collections.singleton("Operator"), null, "phi:audit"));
			assertTrue(evaluator.evaluate(caller("root", "Operator"), DataPermission.AUDIT, null).isAllowed());
		}
	}

	@Nested
	@DisplayName("scope: the record has to match too")
	class Scope {

		private final AccessEvaluator evaluator = evaluator(
				rule("cardiology supervisors hear cardiology", Collections.singleton("supervisors"),
						record("queue", "cardiology"), "phi:play"));

		@Test
		@DisplayName("their own queue is allowed")
		void ownQueueAllowed() {
			assertTrue(evaluator.evaluate(caller("sam", "supervisors"), DataPermission.PLAY,
					record("queue", "cardiology")).isAllowed());
		}

		@Test
		@DisplayName("another queue is refused, though the caller and the permission are right")
		void otherQueueRefused() {
			assertFalse(evaluator.evaluate(caller("sam", "supervisors"), DataPermission.PLAY,
					record("queue", "oncology")).isAllowed());
		}

		@Test
		@DisplayName("a record missing the attribute the rule names is refused, not waved through")
		void missingRecordAttributeRefused() {
			assertFalse(evaluator.evaluate(caller("sam", "supervisors"), DataPermission.PLAY, record()).isAllowed());
			assertFalse(evaluator.evaluate(caller("sam", "supervisors"), DataPermission.PLAY, null).isAllowed());
		}

		@Test
		@DisplayName("${subject.name} gives each agent their own calls with one rule")
		void ownCallsBySubjectName() {
			AccessEvaluator evaluator = evaluator(rule("agents hear their own calls", null,
					record("agent", "${subject.name}"), "phi:play"));
			assertTrue(evaluator.evaluate(caller("alice"), DataPermission.PLAY, record("agent", "alice")).isAllowed());
			assertFalse(evaluator.evaluate(caller("alice"), DataPermission.PLAY, record("agent", "bob")).isAllowed());
		}

		@Test
		@DisplayName("a rule referencing an attribute the deployment does not supply matches nothing")
		void unresolvableSubjectAttributeRefused() {
			AccessEvaluator evaluator = evaluator(
					rule("same team", null, record("team", "${subject.team}"), "phi:play"));
			// The container path supplies no attributes, so this fails closed
			// rather than comparing null to null and letting everyone through.
			assertFalse(evaluator.evaluate(caller("alice"), DataPermission.PLAY, record("team", "cardiology"))
					.isAllowed());
			assertTrue(evaluator.evaluate(
					SubjectAttributes.of("alice", Collections.emptySet(), record("team", "cardiology")),
					DataPermission.PLAY, record("team", "cardiology")).isAllowed());
		}
	}

	@Nested
	@DisplayName("first match wins, and names itself")
	class Ordering {

		@Test
		@DisplayName("the decision carries the rule that granted it")
		void decisionNamesTheRule() {
			AccessDecision decision = evaluator(rule("compliance may export", Collections.singleton("compliance"),
					null, "phi:export")).evaluate(caller("dana", "compliance"), DataPermission.EXPORT, null);
			assertTrue(decision.isAllowed());
			assertEquals("compliance may export", decision.getRule());
		}

		@Test
		@DisplayName("a later rule cannot be reached through an earlier one that does not grant the permission")
		void laterRuleStillConsidered() {
			AccessEvaluator evaluator = evaluator(
					rule("first", Collections.singleton("compliance"), null, "phi:list"),
					rule("second", Collections.singleton("compliance"), null, "phi:export"));
			AccessDecision decision = evaluator.evaluate(caller("dana", "compliance"), DataPermission.EXPORT, null);
			assertTrue(decision.isAllowed());
			assertEquals("second", decision.getRule());
		}

		@Test
		@DisplayName("a deny still says what was attempted")
		void denyNamesThePermission() {
			AccessDecision decision = evaluator().evaluate(caller("alice"), DataPermission.EXPORT, null);
			assertEquals(DataPermission.EXPORT, decision.getPermission());
		}
	}

	@Nested
	@DisplayName("break-glass")
	class BreakGlass {

		private final AccessEvaluator evaluator = evaluator(
				rule("on-call may break glass", Collections.singleton("on-call"), null, "phi:breakglass"));

		@Test
		@DisplayName("grants what an ordinary evaluation refused, and is flagged as itself")
		void grantsAndFlags() {
			SubjectAttributes pat = caller("pat", "on-call");
			assertFalse(evaluator.evaluate(pat, DataPermission.PLAY, null).isAllowed());

			AccessDecision decision = evaluator.breakGlass(pat, DataPermission.PLAY, null, "ambulance en route");
			assertTrue(decision.isAllowed());
			assertTrue(decision.isBreakGlass());
			assertTrue(decision.getRule().contains("ambulance en route"));
		}

		@Test
		@DisplayName("is refused without a justification, because the justification is the record")
		void requiresJustification() {
			assertFalse(evaluator.breakGlass(caller("pat", "on-call"), DataPermission.PLAY, null, "  ").isAllowed());
			assertFalse(evaluator.breakGlass(caller("pat", "on-call"), DataPermission.PLAY, null, null).isAllowed());
		}

		@Test
		@DisplayName("is refused to a caller the policy did not grant it to")
		void notForEveryone() {
			assertFalse(evaluator.breakGlass(caller("bob", "billing"), DataPermission.PLAY, null, "curious")
					.isAllowed());
		}

		@Test
		@DisplayName("never happens as a fallback inside an ordinary evaluation")
		void neverAutomatic() {
			AccessDecision decision = evaluator.evaluate(caller("pat", "on-call"), DataPermission.PLAY, null);
			assertFalse(decision.isAllowed());
			assertFalse(decision.isBreakGlass());
		}
	}

	@Nested
	@DisplayName("subject adapters")
	class Adapters {

		@Test
		@DisplayName("a realm subject's non-user principals are its groups")
		void realmSubject() {
			Subject subject = new Subject();
			subject.getPrincipals().add(named("alice"));
			subject.getPrincipals().add(named("qa-reviewers"));
			subject.getPrincipals().add(named("cardiology"));

			SubjectAttributes attributes = RealmSubjectAttributes.of(subject, "alice");
			assertNotNull(attributes);
			assertEquals("alice", attributes.name());
			assertTrue(attributes.inGroup("qa-reviewers"));
			assertTrue(attributes.inGroup("cardiology"));
			assertFalse(attributes.inGroup("alice"));
			assertTrue(attributes.attributes().isEmpty());
		}

		@Test
		@DisplayName("a bearer token contributes the groups the claim carried, not the admin roles it mapped to")
		void tokenSubjectUsesRawGroups() {
			JwtIdentity identity = new JwtIdentity("alice", Collections.singleton("Admin"),
					Collections.singletonMap("team", "cardiology"),
					new LinkedHashSet<>(Arrays.asList("acme-blade-admins", "qa-reviewers")));

			SubjectAttributes attributes = SubjectAttributes.of(identity);
			assertTrue(attributes.inGroup("qa-reviewers"));
			assertTrue(attributes.inGroup("acme-blade-admins"));
			// Using roles() here would mean only admins could match any rule.
			assertFalse(attributes.inGroup("Admin"));
			assertEquals("cardiology", attributes.attribute("team"));
		}

		@Test
		@DisplayName("a realm subject with no username is nobody, and denies")
		void realmSubjectNeedsAName() {
			assertNull(RealmSubjectAttributes.of(new Subject(), null));
			assertNull(RealmSubjectAttributes.of(null, "alice"));
		}
	}

	private static java.security.Principal named(String name) {
		return () -> name;
	}
}
