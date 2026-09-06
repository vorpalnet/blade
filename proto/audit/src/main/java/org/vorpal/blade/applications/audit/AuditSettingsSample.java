package org.vorpal.blade.applications.audit;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Arrays;

import org.vorpal.blade.framework.v3.security.AccessPolicy;
import org.vorpal.blade.framework.v3.security.AccessRule;

/// Sample policy: one group reads the log, and nobody else.
///
/// Deliberately a single narrow rule. The sample for a surface this sensitive
/// should look like the least a deployment could grant, not a menu of options.
public class AuditSettingsSample extends AuditSettings {
	private static final long serialVersionUID = 1L;

	public AuditSettingsSample() {
		AccessRule auditors = new AccessRule();
		auditors.setName("auditors read the access log");
		auditors.setGroups(new LinkedList<>(Arrays.asList("EXAMPLE-AUDITORS")));
		auditors.setMatch(new LinkedHashMap<>());
		auditors.setPermit(new LinkedList<>(Arrays.asList("phi:audit")));

		AccessPolicy policy = new AccessPolicy();
		policy.setRules(new LinkedList<>(Arrays.asList(auditors)));
		setAccess(policy);
	}
}
