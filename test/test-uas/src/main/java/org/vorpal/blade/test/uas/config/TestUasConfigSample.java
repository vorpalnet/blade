package org.vorpal.blade.test.uas.config;

import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

/// Sample configuration written to `config/custom/vorpal/test-uas.json.SAMPLE`
/// and used to generate the JSON Schema the Configurator renders.
///
/// Besides the inherited logging/session defaults, this populates the
/// display metadata (`name`/`tagline`/`description`) on the inherited `about`
/// object, which the BLADE Admin Portal and Configurator read from each app's
/// `Configuration` via the `SettingsMXBean.getCurrentJson()` JMX attribute.
/// test-uas has no app-specific settings — its behavior comes entirely from
/// the Request-URI.
public class TestUasConfigSample extends TestUasConfig {

	public TestUasConfigSample() {
		this.about.setName("Test UAS")
				.setTagline("Scriptable SIP endpoint for call-path testing")
				.setDescription("A SIP test server that sits at the end of the call path. "
						+ "With no Request-URI parameters it strips multipart/SIPREC bodies down to SDP "
						+ "and forwards the call; with status/delay/refer parameters it answers locally — "
						+ "letting you mock up endpoints, error responses, holds, and transfers without SIPp.");

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINEST);
		this.session = new SessionParameters();
		this.session.setExpiration(60);
	}

}
