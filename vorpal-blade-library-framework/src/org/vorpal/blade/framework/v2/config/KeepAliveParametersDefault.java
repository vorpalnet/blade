package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;

public class KeepAliveParametersDefault extends KeepAliveParameters implements Serializable{

	public KeepAliveParametersDefault() {
		this.style = KeepAlive.DISABLED;
		this.sessionExpires = 3600;
		this.minSE = 1800;
	}

}
