package org.vorpal.blade.applications.callflow;

import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// Sample configuration; populates the `about` metadata the Admin Portal
/// launcher deck reads over JMX.
public class CallflowSettingsSample extends CallflowSettings {
	private static final long serialVersionUID = 1L;

	public CallflowSettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINE);
	}
}
