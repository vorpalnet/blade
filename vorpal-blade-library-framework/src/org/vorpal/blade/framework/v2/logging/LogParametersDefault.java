package org.vorpal.blade.framework.v2.logging;

import java.io.Serializable;

public class LogParametersDefault extends LogParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	public LogParametersDefault() {
		this.useParentLogging = false;
		this.filename = "${sip.application.name}.%g.log";
		this.directory = "./servers/${weblogic.Name}/logs/vorpal";
		this.fileSize = "100MiB";
		this.fileCount = 25;
		this.appendFile = true;
		this.loggingLevel = LoggingLevel.FINE;
		this.sequenceDiagramLoggingLevel = LoggingLevel.FINE;
		this.configurationLoggingLevel = LoggingLevel.FINE;
	}

}
