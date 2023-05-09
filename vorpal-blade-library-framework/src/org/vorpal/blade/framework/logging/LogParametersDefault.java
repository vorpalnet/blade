package org.vorpal.blade.framework.logging;

import java.io.Serializable;

public class LogParametersDefault extends LogParameters implements Serializable {

	private static final long serialVersionUID = 1L;

	public LogParametersDefault() {
		useParentHandlers = false;
		directory = "./servers/${weblogic.Name}/logs/vorpal";
		name = "${sip.application.name}";
		limit = 50 * 1024 * 1024;
		count = 25;
		append = false;
		level = LoggingLevel.INFO;
	}

}
