package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.List;

import org.vorpal.blade.framework.v3.config.AttributeSelector;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public class SessionParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	public enum DialogType {
		origin, destination
	};

	@JsonPropertyDescription("Set Application Session expiration in minutes.")
	public Integer expiration = null;

	@JsonPropertyDescription("Set Keep-Alive parameters.")
	public KeepAliveParameters keepAlive = null;

	@JsonPropertyDescription("Apply attributes to either origin or destination dialog. Default: origin")
	public DialogType dialog;

	@JsonPropertyDescription("List of selectors for creating session (SipApplicationSession) lookup keys.")
	public List<AttributeSelector> sessionSelectors = null;

	public DialogType getDialog() {

		if (dialog == null) {
			return DialogType.origin;
		} else {
			return dialog;
		}
	}

	public SessionParameters setDialog(DialogType dialog) {
		this.dialog = dialog;
		return this;
	}

	public List<AttributeSelector> getSessionSelectors() {
		return sessionSelectors;
	}

	public void setSessionSelectors(List<AttributeSelector> sessionSelectors) {
		this.sessionSelectors = sessionSelectors;
	}

	public Integer getExpiration() {
		return expiration;
	}

	public SessionParameters setExpiration(Integer expiration) {
		this.expiration = expiration;
		return this;
	}

	public KeepAliveParameters getKeepAlive() {
		return keepAlive;
	}

	public SessionParameters setKeepAlive(KeepAliveParameters keepAlive) {
		this.keepAlive = keepAlive;
		return this;
	}

}
