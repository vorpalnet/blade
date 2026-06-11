package org.vorpal.blade.applications.testconsole;

import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// Sample configuration; populates the `about` metadata the Admin Portal
/// launcher deck reads over JMX.
public class TestConsoleSettingsSample extends TestConsoleSettings {
	private static final long serialVersionUID = 1L;

	public TestConsoleSettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINE);
	}
}
