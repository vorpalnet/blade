package org.vorpal.blade.services.proxy.registrar.v3;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class SettingsSample extends Settings {

	public SettingsSample() {
		this.logging = new LogParametersDefault();
		this.session = new SessionParametersDefault();
		this.session.setSessionSelectors(null);
		this.logging.setLoggingLevel(LoggingLevel.FINER);

		this.addToPath = false;
		this.parallel = true;
		this.proxyOnUnregistered = false;
		this.proxyTimeout = 180;
		this.recordRoute = false;
		this.supervised = false;
	}

}
