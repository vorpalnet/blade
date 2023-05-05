package org.vorpal.blade.framework.logging;

import java.io.Serializable;
import java.util.logging.Level;

public class LogParametersDefault extends LogParameters implements Serializable {

	private static final long serialVersionUID = 1L;

	public LogParametersDefault() {
		useParent = false;
		directory = "./servers/${weblogic.Name}/logs/vorpal";
		name = "${sip.application.name}";
		limit = 50 * 1024 * 1024;
		count = 25;
		append = false;
		level = Level.INFO;
	}

}
