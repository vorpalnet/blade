package org.vorpal.blade.services.analytics.sip;

import org.vorpal.blade.framework.v2.analytics.AnalyticsB2buaSample;
import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class AnalyticsConfigSample extends AnalyticsConfig {

	private static final long serialVersionUID = 1L;

	public AnalyticsConfigSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.INFO);
		this.session = new SessionParametersDefault();
		this.analytics = new AnalyticsB2buaSample();

		// No `events` or `analytics` switch: unset, the bus is used when it is
		// provisioned and the sink subscribes once a database is set up
		// (DatabaseProbe), each saying so in one line when it is not.

		this.healthCheckSql = "SELECT 1";
		this.healthCheckInterval = 60;
		this.domainId = "SIPREC-03";

	}
}
