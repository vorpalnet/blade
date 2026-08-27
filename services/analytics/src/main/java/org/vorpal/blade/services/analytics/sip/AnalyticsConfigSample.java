package org.vorpal.blade.services.analytics.sip;

import org.vorpal.blade.framework.v2.analytics.AnalyticsB2buaSample;
import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;
import org.vorpal.blade.framework.v3.events.EventBusSettings;

public class AnalyticsConfigSample extends AnalyticsConfig {

	private static final long serialVersionUID = 1L;

	public AnalyticsConfigSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.INFO);
		this.session = new SessionParametersDefault();
		this.analytics = new AnalyticsB2buaSample();

		// On, in the sample for the analytics service specifically.
		//
		// The framework default is off, which is right for an ordinary
		// application — one that does not publish should not hold a JMS
		// connection open. It is the wrong default for THIS one: a domain with
		// no analytics.json runs on this sample, and an analytics service that
		// starts up disabled is the exact failure this whole area kept
		// producing — everything reports healthy and nothing is recorded.
		this.analytics.setEnabled(true);
		this.events = new EventBusSettings();
		this.events.setEnabled(true);

		this.healthCheckSql = "SELECT 1";
		this.healthCheckInterval = 60;
		this.domainId = "SIPREC-03";

	}
}
