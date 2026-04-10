package org.vorpal.blade.test.b2bua;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.analytics.AnalyticsB2buaSample;
import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class ConfigSample extends SampleB2buaConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	public ConfigSample() {

		try {
			this.logging = new LogParametersDefault();
			this.logging.setLoggingLevel(LoggingLevel.FINEST);
			this.session = new SessionParametersDefault();
			this.analytics = new AnalyticsB2buaSample();

			this.traveler = "{CLEARTEXT}Sir Lancelot of Camelot";
			this.quest = "{CLEARTEXT}To seek the Holy Grail";
			this.color = "Blue";
			
			
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
