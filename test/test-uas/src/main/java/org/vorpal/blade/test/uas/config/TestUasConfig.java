package org.vorpal.blade.test.uas.config;

import org.vorpal.blade.framework.v3.tester.TesterConfiguration;

/// Configuration for the BLADE Test UAS. All app-specific settings —
/// scenarios, rule sets, originate defaults, scenario selection — come from
/// the inherited [TesterConfiguration]. URI-parameter shorthands (`status`,
/// `delay`, `refer`) keep working with an empty configuration.
public class TestUasConfig extends TesterConfiguration {
	private static final long serialVersionUID = 1L;

	public TestUasConfig() {
	}
}
