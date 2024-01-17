package org.vorpal.blade.framework.config;

import java.text.ParseException;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class KeepAliveParameters {
	public enum KeepAlive {
		DISABLED, UPDATE, REINVITE
	};

	@JsonPropertyDescription("Sets keep alive style: DISABLED, UPDATE, REINVITE")
	protected KeepAlive style = null;

	@JsonPropertyDescription("Sets Session-Expires header, in seconds")
	protected Integer sessionExpires = null;

	@JsonPropertyDescription("Sets Min-SE header, in seconds")
	protected Integer minSE = null;

	public KeepAlive getStyle() {
		return style;
	}

	public KeepAliveParameters setStyle(KeepAlive style) {
		this.style = style;
		return this;
	}

	public Integer getSessionExpires() {
		return sessionExpires;
	}

	public KeepAliveParameters setSessionExpires(Integer sessionExpires) {
		this.sessionExpires = sessionExpires;
		return this;
	}

	public Integer getMinSE() {
		return minSE;
	}

	public KeepAliveParameters setMinSE(Integer minSE) {
		this.minSE = minSE;
		return this;
	}

}
