package org.vorpal.blade.framework.config;

import java.text.ParseException;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SessionParametersDefault extends SessionParameters {

	public SessionParametersDefault() {
		this.expiration = 3;
		this.keepAlive = new KeepAliveParametersDefault();
	}

}
