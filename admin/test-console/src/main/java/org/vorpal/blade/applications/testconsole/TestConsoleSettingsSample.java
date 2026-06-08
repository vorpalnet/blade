package org.vorpal.blade.applications.testconsole;

import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// Sample configuration; populates the `about` metadata the Admin Portal
/// launcher deck reads over JMX.
public class TestConsoleSettingsSample extends TestConsoleSettings {
	private static final long serialVersionUID = 1L;

	public TestConsoleSettingsSample() {
		this.about.setName("Test Console")
				.setTagline("Cluster-wide SIP test runs and live metrics")
				.setDescription("Drive the BLADE test apps (test-uac, test-uas) across the whole cluster: "
						+ "start and stop load runs on every node at once, and watch live per-scenario "
						+ "metrics — call counts, status distributions, latency percentiles, assertion "
						+ "pass/fail — aggregated over federated JMX. No SIPp required.");

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINE);
	}
}
