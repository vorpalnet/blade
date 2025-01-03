package org.vorpal.blade.test.uas.config;

import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

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
