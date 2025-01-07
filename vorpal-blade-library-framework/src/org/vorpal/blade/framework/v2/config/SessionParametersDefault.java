package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;

public class SessionParametersDefault extends SessionParameters implements Serializable{
	private static final long serialVersionUID = 1L;

	public SessionParametersDefault() {
		this.expiration = 3;
		this.keepAlive = new KeepAliveParametersDefault();
	}

}
