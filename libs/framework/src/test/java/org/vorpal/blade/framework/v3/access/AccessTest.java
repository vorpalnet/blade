package org.vorpal.blade.framework.v3.access;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.vorpal.blade.framework.v3.security.AccessEvaluator;
import org.vorpal.blade.framework.v3.security.AccessPolicy;
import org.vorpal.blade.framework.v3.security.AccessRule;
import org.vorpal.blade.framework.v3.security.SubjectAttributes;

/// Whether a part of a call, by its domain, tenant and department, is one a
/// viewer's grants show, and whether its content is one the policy lets them
/// read.
class AccessTest {

	private static AccessEvaluator policy(AccessRule... rules) {
		AccessPolicy p = new AccessPolicy();
		p.getRules().addAll(Arrays.asList(rules));
		return new AccessEvaluator(p);
	}

	private static AccessRule rule(String group, Map<String, String> match) {
		return new AccessRule(group, Arrays.asList(group), match, Arrays.asList("phi:transcript"));
	}

	private static SubjectAttributes in(String... groups) {
		return SubjectAttributes.of("viewer", new HashSet<>(Arrays.asList(groups)));
	}

	private static Grant grant(String domain, String tenant, String department) {
		Grant g = new Grant();
		g.setDomains(Arrays.asList(domain));
		g.setTenants(Arrays.asList(tenant));
		g.setDepartments(Arrays.asList(department));
		return g;
	}

	@Test
	void everyListMustMatch() {
		Access a = Access.of(Arrays.asList(grant("ashburn", "tenant_001", "radiology")));
		assertTrue(a.seesCall("ashburn", "tenant_001", "radiology"));
		assertFalse(a.seesCall("west", "tenant_001", "radiology"));
		assertFalse(a.seesCall("ashburn", "tenant_002", "radiology"));
		assertFalse(a.seesCall("ashburn", "tenant_001", "sales"));
	}

	@Test
	void aLimitedListNeverMatchesAMissingValue() {
		Access a = Access.of(Arrays.asList(grant("*", "tenant_001", "*")));
		assertTrue(a.seesCall("ashburn", "tenant_001", null), "every department, including none");
		assertFalse(a.seesCall("ashburn", null, null), "a call with no tenant is not tenant_001's");
		assertTrue(Access.of(Arrays.asList(grant("*", "*", "*"))).seesCall(null, null, null));
	}

	@Test
	void grantsAreAUnion() {
		Access a = Access.of(Arrays.asList(grant("*", "tenant_001", "radiology"), grant("*", "tenant_002", "*")));
		assertTrue(a.seesCall("x", "tenant_002", "anything"));
		assertTrue(a.seesCall("x", "tenant_001", "radiology"));
		assertFalse(a.seesCall("x", "tenant_001", "sales"));
	}

	@Test
	void grantsGiveNoContent() {
		Access a = Access.of(Arrays.asList(grant("*", "*", "*")));
		assertTrue(a.seesAllCalls());
		assertFalse(a.seesContent());
		assertFalse(a.seesContent("ashburn", "tenant_001", "radiology"));
	}

	@Test
	void aRuleWithNoMatchGivesContentEverywhere() {
		Access a = Access.all().withContent(in("Administrators"),
				policy(rule("Administrators", Collections.<String, String>emptyMap())));
		assertTrue(a.seesContent());
		assertTrue(a.seesContent("ashburn", null, null));
		assertFalse(Access.all().withContent(in("Monitors"),
				policy(rule("Administrators", Collections.<String, String>emptyMap()))).seesContent(),
				"a rule about another group");
	}

	@Test
	void aRuleWithAMatchGivesContentPerRecordOnly() {
		Map<String, String> radiology = new LinkedHashMap<>();
		radiology.put("department", "radiology");
		Access a = Access.of(Arrays.asList(grant("*", "tenant_001", "*")))
				.withContent(in("radiology-supervisors"), policy(rule("radiology-supervisors", radiology)));
		assertTrue(a.seesContent("ashburn", "tenant_001", "radiology"));
		assertFalse(a.seesContent("ashburn", "tenant_001", "sales"));
		assertFalse(a.seesContent(), "not every call it sees, so no whole-query search");
	}

	@Test
	void theDefaultPolicyIsTheAdministratorGroups() {
		AccessEvaluator defaults = new AccessEvaluator(Access.defaultPolicy());
		assertTrue(Access.all().withContent(signedIn(SubjectAttributes.WEBLOGIC, "Administrators"), defaults)
				.seesContent());
		assertTrue(Access.all().withContent(signedIn(SubjectAttributes.WEBLOGIC, "Monitors"), defaults).seesContent());
		assertFalse(Access.all().withContent(signedIn(SubjectAttributes.WEBLOGIC, "Reviewer"), defaults).seesContent());
		assertFalse(Access.all().withContent(null, defaults).seesContent(), "nobody signed in");
	}

	@Test
	void theDefaultPolicyIsForWebLogicSignInsOnly() {
		AccessEvaluator defaults = new AccessEvaluator(Access.defaultPolicy());
		assertFalse(Access.all().withContent(signedIn(SubjectAttributes.OIDC, "Administrators"), defaults).seesContent(),
				"an identity domain's own Administrators group is the cloud account's, not WebLogic's");
		assertFalse(Access.all().withContent(in("Administrators"), defaults).seesContent(),
				"a sign-in nobody can name fails closed");
	}

	@Test
	void aRuleWithNoSignInAdmitsEither() {
		AccessEvaluator readers = policy(rule("readers", null));
		assertTrue(Access.all().withContent(signedIn(SubjectAttributes.OIDC, "readers"), readers).seesContent());
		assertTrue(Access.all().withContent(signedIn(SubjectAttributes.WEBLOGIC, "readers"), readers).seesContent());
	}

	private static SubjectAttributes signedIn(String signIn, String... groups) {
		return SubjectAttributes.of("viewer", new HashSet<>(Arrays.asList(groups)), null, signIn);
	}
}
