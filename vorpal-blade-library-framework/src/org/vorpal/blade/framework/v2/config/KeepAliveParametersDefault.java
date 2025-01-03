package org.vorpal.blade.framework.v2.config;

public class KeepAliveParametersDefault extends KeepAliveParameters {

	public KeepAliveParametersDefault() {
		this.style = KeepAlive.DISABLED;
		this.sessionExpires = 3600;
		this.minSE = 1800;
	}

}
