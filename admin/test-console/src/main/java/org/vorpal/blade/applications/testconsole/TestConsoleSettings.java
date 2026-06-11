package org.vorpal.blade.applications.testconsole;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Settings for the Test Console admin app. The console is a pure JMX
/// dashboard — every tester node is discovered at runtime — so this carries
/// only the inherited `about` metadata (for the Admin Portal launcher deck)
/// and logging parameters.
@SchemaAbout(
		name = "Test Console",
		tagline = "Cluster-wide SIP test runs and live metrics",
		description = "Drive the BLADE test apps (test-uac, test-uas) across the whole cluster: " + "start and stop load runs on every node at once, and watch live per-scenario " + "metrics — call counts, status distributions, latency percentiles, assertion " + "pass/fail — aggregated over federated JMX. No SIPp required.")
public class TestConsoleSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;

	public TestConsoleSettings() {
	}
}
