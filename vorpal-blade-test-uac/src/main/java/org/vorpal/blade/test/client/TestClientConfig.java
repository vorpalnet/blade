package org.vorpal.blade.test.client;

import java.io.Serializable;

import org.vorpal.blade.framework.config.Configuration;
import org.vorpal.blade.framework.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class TestClientConfig extends Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
	public String version = "1.0"; // just a placeholder

	public TestClientConfig() {
	}
}
