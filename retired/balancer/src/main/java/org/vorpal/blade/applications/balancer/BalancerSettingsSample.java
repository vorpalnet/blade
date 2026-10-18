package org.vorpal.blade.applications.balancer;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class BalancerSettingsSample extends BalancerSettings implements Serializable {
	private static final long serialVersionUID = 1L;

	public BalancerSettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.INFO);
	}
}
