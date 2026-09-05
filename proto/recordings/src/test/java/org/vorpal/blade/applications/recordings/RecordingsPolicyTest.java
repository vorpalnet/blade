package org.vorpal.blade.applications.recordings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.security.AccessDecision;
import org.vorpal.blade.framework.v3.security.AccessEvaluator;
import org.vorpal.blade.framework.v3.security.DataPermission;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

/// What the shipped sample policy actually grants.
///
/// A sample is configuration people copy, so its behaviour is worth pinning: the
/// cases that matter are the ones where a plainly legitimate caller is still
/// refused, because those are the ones a reviewer will call a bug.
class RecordingsPolicyTest {

	private static AccessEvaluator sample() {
		return new AccessEvaluator(new RecordingsSettingsSample().getAccess());
	}

	private static SubjectAttributes caller(String name, String... groups) {
		return SubjectAttributes.of(name, new LinkedHashSet<>(Arrays.asList(groups)));
	}

	private static Map<String, String> recording(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	@Test
	@DisplayName("an agent hears their own call and not a colleague's")
	void agentsHearTheirOwnCalls() {
		AccessEvaluator policy = sample();
		assertTrue(policy.evaluate(caller("alice"), DataPermission.PLAY,
				recording("agent", "alice")).isAllowed());
		assertFalse(policy.evaluate(caller("alice"), DataPermission.PLAY,
				recording("agent", "bob")).isAllowed());
	}

	@Test
	@DisplayName("an agent cannot export the call they are allowed to hear")
	void playIsNotExport() {
		assertFalse(sample().evaluate(caller("alice"), DataPermission.EXPORT,
				recording("agent", "alice")).isAllowed());
	}

	@Test
	@DisplayName("a platform Admin who no rule names hears nothing")
	void adminIsNotAccess() {
		AccessEvaluator policy = sample();
		SubjectAttributes root = caller("root", "Admin", "Operator", "Deployer", "Monitor");
		assertFalse(policy.evaluate(root, DataPermission.LIST, recording("agent", "alice")).isAllowed());
		assertFalse(policy.evaluate(root, DataPermission.PLAY, recording("agent", "alice")).isAllowed());
	}

	@Test
	@DisplayName("a supervisor is confined to their own queue")
	void supervisorsAreScoped() {
		AccessEvaluator policy = sample();
		SubjectAttributes sam = caller("sam", "EXAMPLE-SUPERVISORS");
		assertTrue(policy.evaluate(sam, DataPermission.PLAY,
				recording("queue", "EXAMPLE-QUEUE")).isAllowed());
		assertFalse(policy.evaluate(sam, DataPermission.PLAY,
				recording("queue", "SOME-OTHER-QUEUE")).isAllowed());
	}

	@Test
	@DisplayName("a recording with no attributes matches no scoped rule")
	void unknownRecordingsFailClosed() {
		// The listing path hands through whatever the archive knows. A recording
		// the archive cannot describe must not fall through to a grant.
		AccessEvaluator policy = sample();
		assertFalse(policy.evaluate(caller("alice"), DataPermission.PLAY, recording()).isAllowed());
		assertFalse(policy.evaluate(caller("sam", "EXAMPLE-SUPERVISORS"), DataPermission.PLAY,
				recording()).isAllowed());
	}

	@Test
	@DisplayName("a refusal still says what was attempted, so the audit record is reviewable")
	void refusalsNameThePermission() {
		AccessDecision decision = sample().evaluate(caller("mallory"), DataPermission.EXPORT,
				recording("agent", "alice"));
		assertFalse(decision.isAllowed());
		assertEquals(DataPermission.EXPORT, decision.getPermission());
	}
}
