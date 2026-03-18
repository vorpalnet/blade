package org.vorpal.blade.test.uac;

import java.util.logging.Level;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class UserAgentClientConfigSample extends UserAgentClientConfig {

	private static final long serialVersionUID = 1L;

	public UserAgentClientConfigSample() {

		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINEST);
		this.session = new SessionParametersDefault();
		this.session.setExpiration(180);

		this.headers.put("Min-SE", "90");
		this.headers.put("Session-Expires", "2400;refresher=uac");
		this.headers.put("Supported", "timer");
		this.headers.put("X-Genesys-CallUUID", "123potatoXYZ");

	}
}
