package org.vorpal.blade.framework.v2.logging;

import java.io.Serializable;

/**
 * Default logging configuration values.
 * Provides sensible defaults for log directory, file size, rotation count, and logging levels.
 */
public class LogParametersDefault extends LogParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	public LogParametersDefault() {
		this.useParentLogging = false;
		this.fileName = "${sip.application.name}.%g.log";
		this.directory = "./servers/${weblogic.Name}/logs/vorpal";
		this.fileSize = "100MiB";
		this.fileCount = 25;
		this.appendFile = true;
		this.loggingLevel = LoggingLevel.INFO;
		this.sequenceDiagramLoggingLevel = LoggingLevel.FINE;
		this.configurationLoggingLevel = LoggingLevel.INFO;
	}

}
