package org.vorpal.blade.framework.config;

import java.text.ParseException;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SessionParameters {
	public enum KeepAliveStyle {
		DISABLED, UPDATE, INVITE
	};

	@JsonPropertyDescription("Set Application Session expiration in minutes.")
	protected Boolean expiration = null;

	@JsonPropertyDescription("Set keep alive style of DISABLED, UPDATE, OPTIONS, REINVITE")
	protected KeepAliveStyle keepalive = null;

	@JsonPropertyDescription("Sets Min-SE header, in seconds")
	protected Integer minSE = null;

	@JsonPropertyDescription("Sets Session-Expires header, in seconds")
	protected Integer sessionExpires = null;

}
