package org.vorpal.blade.services.tpcc;

import org.vorpal.blade.framework.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.logging.LogParametersDefault;

public class TpccSettingsSample extends TpccSettings {

	private static final long serialVersionUID = 1L;

	public TpccSettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.FINE);

	}

}
