package org.vorpal.blade.test.b2bua;

import java.io.Serializable;
import java.util.HashMap;

import org.vorpal.blade.framework.v2.callflow.Callflow;
import org.vorpal.blade.framework.v2.config.KeepAliveParameters;
import org.vorpal.blade.framework.v2.config.SessionParameters;
import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class ConfigSample extends SampleB2buaConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	public ConfigSample() {

		try {
			this.logging = new LogParametersDefault();
			this.logging.setLoggingLevel(LoggingLevel.FINER);
			this.session = new SessionParametersDefault();

			this.value1 = "one";
			this.value2 = "two";

			SessionParameters sp = new SessionParameters();
			sp.setExpiration(900);

			KeepAliveParameters kap = new KeepAliveParameters();
			kap.setMinSE(90);
			kap.setSessionExpires(180);
			kap.setStyle(KeepAliveParameters.KeepAlive.REINVITE);

			sp.setKeepAlive(kap);
			this.setSession(sp);

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
