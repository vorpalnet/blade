package org.vorpal.blade.services.analytics.sip;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class AnalyticsConfigSample extends AnalyticsConfig {

	private static final long serialVersionUID = 1L;

	public AnalyticsConfigSample() {

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);
		this.session = new SessionParametersDefault();
		this.analytics = new AnalyticsSample();

		this.someValue = "value1";
	}
}
