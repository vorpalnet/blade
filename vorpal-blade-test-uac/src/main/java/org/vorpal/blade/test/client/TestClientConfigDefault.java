package org.vorpal.blade.test.client;

import java.io.Serializable;

import org.vorpal.blade.framework.config.SessionParameters;
import org.vorpal.blade.framework.logging.LogParameters;
import org.vorpal.blade.framework.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class TestClientConfigDefault extends TestClientConfig implements Serializable {
	public TestClientConfigDefault() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);
		this.session = new SessionParameters();
		this.session.setExpiration(900);
	}
}
