package org.vorpal.blade.test.b2bua;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class ConfigSample extends SampleB2buaConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	public ConfigSample() {

		try {
			this.logging = new LogParametersDefault();
			this.session = new SessionParametersDefault();

			this.value1 = "one";
			this.value2 = "two";
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
