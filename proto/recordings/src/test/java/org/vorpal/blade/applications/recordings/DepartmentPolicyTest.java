package org.vorpal.blade.applications.recordings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.security.AccessEvaluator;
import org.vorpal.blade.framework.v3.security.AccessPolicy;
import org.vorpal.blade.framework.v3.security.AccessRule;
import org.vorpal.blade.framework.v3.security.DataPermission;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

/// Rules that turn on a recording's stored attributes rather than on the caller
/// alone.
///
/// `department` is the worked example, and the reason these are separate from
/// [RecordingsPolicyTest]: those exercise the shipped sample policy, these
/// exercise the mechanism a customer's policy uses once recordings carry
/// business attributes. The attributes here stand in for what
/// `RecordingArchive.attributes` returns.
class DepartmentPolicyTest {

	private static SubjectAttributes caller(String name, Map<String, String> attributes, String... groups) {
		return SubjectAttributes.of(name, new LinkedHashSet<>(Arrays.asList(groups)), attributes);
	}

	private static LinkedHashMap<String, String> map(String... pairs) {
		LinkedHashMap<String, String> values = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			values.put(pairs[i], pairs[i + 1]);
		}
		return values;
	}

	private static AccessRule rule(String name, LinkedHashMap<String, String> match, DataPermission... permits) {
		AccessRule rule = new AccessRule();
		rule.setName(name);
		rule.setMatch(match);
		java.util.LinkedList<String> permitted = new java.util.LinkedList<>();
		for (DataPermission permission : permits) {
			permitted.add(permission.permissionName());
		}
		rule.setPermit(permitted);
		return rule;
	}

	/// One rule covering every department, by comparing the record's department
	/// against the caller's own. This is the shape that makes departments scale:
	/// adding one is a directory change, not a policy edit.
	private static AccessEvaluator ownDepartment() {
		AccessPolicy policy = new AccessPolicy();
		policy.setRules(new java.util.LinkedList<>(Collections.singletonList(
				rule("staff hear their own department", map("department", "${subject.department}"),
						DataPermission.LIST, DataPermission.PLAY))));
		return new AccessEvaluator(policy);
	}

	@Test
	@DisplayName("a caller hears their own department's call")
	void ownDepartmentIsAllowed() {
		AccessEvaluator policy = ownDepartment();

		assertTrue(policy.evaluate(caller("alice", map("department", "cardiology")), DataPermission.PLAY,
				map("department", "cardiology")).isAllowed());
	}

	@Test
	@DisplayName("and not another department's")
	void otherDepartmentIsRefused() {
		AccessEvaluator policy = ownDepartment();

		assertFalse(policy.evaluate(caller("alice", map("department", "cardiology")), DataPermission.PLAY,
				map("department", "billing")).isAllowed());
	}

	@Test
	@DisplayName("an unclassified recording matches no rule that names department")
	void unclassifiedRecordingIsRefused() {
		// The failure mode that matters operationally: classification did not get
		// written, so the recording carries no department. It must be refused
		// rather than fall through to whoever asks first.
		AccessEvaluator policy = ownDepartment();

		assertFalse(policy.evaluate(caller("alice", map("department", "cardiology")), DataPermission.PLAY,
				Collections.emptyMap()).isAllowed());
	}

	@Test
	@DisplayName("a caller with no department reaches nothing")
	void callerWithoutDepartmentIsRefused() {
		// A rule referencing a subject attribute the deployment does not supply
		// must not degrade into a rule that matches everything.
		AccessEvaluator policy = ownDepartment();

		assertFalse(policy.evaluate(caller("alice", Collections.emptyMap()), DataPermission.PLAY,
				map("department", "cardiology")).isAllowed());
	}

	@Test
	@DisplayName("a fixed-department rule reaches only that department")
	void fixedDepartmentRule() {
		AccessPolicy policy = new AccessPolicy();
		policy.setRules(new java.util.LinkedList<>(Collections.singletonList(rule("billing reviewers", map("department", "billing"),
				DataPermission.LIST, DataPermission.PLAY))));
		AccessEvaluator evaluator = new AccessEvaluator(policy);
		SubjectAttributes reviewer = caller("bob", Collections.emptyMap(), "acme-billing");

		assertTrue(evaluator.evaluate(reviewer, DataPermission.PLAY, map("department", "billing")).isAllowed());
		assertFalse(evaluator.evaluate(reviewer, DataPermission.PLAY, map("department", "cardiology")).isAllowed());
	}

	@Test
	@DisplayName("one conversation of a call is reachable while another is not")
	void conversationsAreSeparatelyGated() {
		// The transfer case. A call that moved from support to billing is two
		// recordings with two departments, so a billing reviewer hears the second
		// and is refused the first. That is the whole reason a conversation, not a
		// call, is the unit of recording.
		AccessEvaluator evaluator = ownDepartment();
		SubjectAttributes reviewer = caller("bob", map("department", "billing"));

		assertFalse(evaluator.evaluate(reviewer, DataPermission.PLAY,
				map("vorpalId", "214D89BC", "department", "support")).isAllowed());
		assertTrue(evaluator.evaluate(reviewer, DataPermission.PLAY,
				map("vorpalId", "214D89BC", "department", "billing")).isAllowed());
	}
}
