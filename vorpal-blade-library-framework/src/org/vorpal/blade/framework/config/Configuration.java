package org.vorpal.blade.framework.config;

import java.io.Serializable;

import javax.servlet.ServletContext;

import org.vorpal.blade.framework.logging.LogParameters;

public class Configuration implements Serializable {
	private static final long serialVersionUID = 1L;
	protected LogParameters logging;

	public LogParameters getLogging() {
		return logging;
	}

	public void setLogging(LogParameters logging) {
		this.logging = logging;
	}

}
