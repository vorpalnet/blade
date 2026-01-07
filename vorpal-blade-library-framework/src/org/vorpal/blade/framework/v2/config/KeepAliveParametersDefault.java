package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;

/**
 * Default keep-alive parameters with standard values.
 */
public class KeepAliveParametersDefault extends KeepAliveParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	public KeepAliveParametersDefault() {
		this.style = KeepAlive.DISABLED;
		this.sessionExpires = 3600;
		this.minSE = 1800;
	}

}
