package org.vorpal.blade.test.client;

import java.io.Serializable;

import org.vorpal.blade.framework.logging.LogParameters;
import org.vorpal.blade.framework.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class TestClientConfigDefault extends TestClientConfig implements Serializable {
	public TestClientConfigDefault() {
		logging = new LogParametersDefault();
		logging.setLoggingLevel(LoggingLevel.FINE);
	}
}
