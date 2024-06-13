package org.vorpal.blade.test.uas.config;

import org.vorpal.blade.framework.config.Configuration;
import org.vorpal.blade.framework.config.SessionParameters;
import org.vorpal.blade.framework.logging.LogParametersDefault;
import org.vorpal.blade.framework.logging.LogParameters.LoggingLevel;

public class TestUasConfigSample extends TestUasConfig {

	public TestUasConfigSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINER);
		this.session = new SessionParameters();
		this.session.setExpiration(900);

		errorMap.put("18165550404", 404);
		errorMap.put("18165550503", 503);
		errorMap.put("18165550607", 607);

	}

}
