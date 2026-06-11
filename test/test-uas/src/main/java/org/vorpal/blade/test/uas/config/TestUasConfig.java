package org.vorpal.blade.test.uas.config;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import org.vorpal.blade.framework.v3.tester.TesterConfiguration;

/// Configuration for the BLADE Test UAS. All app-specific settings —
/// scenarios, rule sets, originate defaults, scenario selection — come from
/// the inherited [TesterConfiguration]. URI-parameter shorthands (`status`,
/// `delay`, `refer`) keep working with an empty configuration.
@SchemaAbout(
		name = "Test UAS",
		tagline = "Scriptable SIP endpoint for call-path testing",
		description = "A SIP test server that sits at the end of the call path. " + "Scenarios — selected per call by Request-URI parameter or translation plan — " + "script response sequences, transfers, and message transformations (e.g. stripping " + "SIPREC multipart down to SDP). The classic status/delay/refer URI parameters " + "still work without any configuration. No SIPp required.")
public class TestUasConfig extends TesterConfiguration {
	private static final long serialVersionUID = 1L;

	public TestUasConfig() {
	}
}
