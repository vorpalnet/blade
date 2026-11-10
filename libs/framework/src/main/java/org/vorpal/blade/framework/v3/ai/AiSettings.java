package org.vorpal.blade.framework.v3.ai;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// The domain's AI assistant settings, shared by every admin editor that
/// drafts with Claude (the Configurator's "Use AI", the CRUD editor's rule
/// builder): one key, one switch. Stored in `blade-ai.json` beside the other
/// configuration files ([AiConfig]) and edited in the Configurator like any of
/// them, which encrypts the key on Save. Ships disabled: nothing leaves the
/// AdminServer until an operator turns it on and supplies a key, and what the
/// assistant drafts is only ever a proposal for the operator to save.
public class AiSettings implements Serializable {
	private static final long serialVersionUID = 1L;

	/// The model a draft uses unless the settings name another.
	public static final String DEFAULT_MODEL = "claude-opus-5-5";

	protected boolean enabled = false;
	protected String apiKey;
	protected String model = DEFAULT_MODEL;
	protected String baseUrl;
	protected boolean maskNumbers = true;

	@JsonPropertyDescription("Master switch. Off (the default): the AI buttons are hidden and the AdminServer makes no outbound calls.")
	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@JsonPropertyDescription("Anthropic API key. Encrypted on Save; never sent to the browser.")
	@FormLayout(password = true)
	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	@JsonPropertyDescription("Claude model id. Default: claude-opus-5-5.")
	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	@JsonPropertyDescription("Optional API base URL, for a gateway or private endpoint. Empty: the Anthropic API.")
	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	@JsonPropertyDescription("Replace the digits of phone numbers in sample SIP messages before they are sent, and restore them in the answer. On by default: a sample message usually carries a real caller's number.")
	public boolean isMaskNumbers() {
		return maskNumbers;
	}

	public void setMaskNumbers(boolean maskNumbers) {
		this.maskNumbers = maskNumbers;
	}

	/// The key as plain text: a `{CLEARTEXT}` value not yet saved through an
	/// editor loses its prefix. Null when there is none.
	public String plainKey() {
		if (apiKey == null || apiKey.trim().isEmpty()) return null;
		return apiKey.startsWith("{CLEARTEXT}") ? apiKey.substring("{CLEARTEXT}".length()) : apiKey;
	}

	/// Why a draft cannot run, or null when it can.
	public String unavailable() {
		if (!enabled) return "the AI assistant is off; turn it on in blade-ai (Configurator)";
		if (plainKey() == null) return "no API key; set apiKey in blade-ai (Configurator)";
		return null;
	}
}
