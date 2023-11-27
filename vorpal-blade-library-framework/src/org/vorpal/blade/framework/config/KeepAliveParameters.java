package org.vorpal.blade.framework.config;

import java.text.ParseException;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class KeepAliveParameters {
	public enum KeepAlive {
		DISABLED, UPDATE, OPTIONS, REINVITE
	};

	@JsonPropertyDescription("Sets keep alive style: DISABLED, UPDATE, INVITE")
	protected KeepAlive style = null;

	@JsonPropertyDescription("Sets Session-Expires header, in seconds")
	protected Integer sessionExpires = null;

	@JsonPropertyDescription("Sets Min-SE header, in seconds")
	protected Integer minSE = null;

}
