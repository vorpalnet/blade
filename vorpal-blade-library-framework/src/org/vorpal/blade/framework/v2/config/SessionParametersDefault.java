package org.vorpal.blade.framework.v2.config;

public class SessionParametersDefault extends SessionParameters {

	public SessionParametersDefault() {
		this.expiration = 3;
		this.keepAlive = new KeepAliveParametersDefault();
	}

}
