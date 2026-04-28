package org.vorpal.blade.test.uas.config;

import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class TestUasConfigSample extends TestUasConfig {

	public TestUasConfigSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINEST);
		this.session = new SessionParameters();
		this.session.setExpiration(60);

		this.defaultStatus = 200;
		this.defaultDelay = "0s";
		this.defaultDuration = "30s";
		this.sdpContent = null; // uses built-in default

		// Applied on the B2BUA outbound leg when test-uas forwards
		// to a softphone. Operators copy samples/response-headers.txt
		// → _templates/response-headers.txt.
		this.template = "response-headers.txt";

		errorMap.put("18165550404", 404);
		errorMap.put("18165550503", 503);
		errorMap.put("18165550607", 607);
	}

}
