package org.vorpal.blade.applications.console.config;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Settings for the Configurator's "Use AI" config authoring: the operator
/// describes a configuration in plain language and Claude drafts it against
/// the app's own JSON Schema. Ships disabled; nothing leaves the AdminServer
/// unless an operator turns it on and supplies a key. The generated config is
/// only ever a proposal — it lands in the editor as a diff for review and goes
/// through the normal Save + Publish flow.
public class AiSettings implements Serializable {
	private static final long serialVersionUID = 1L;

	protected boolean enabled = false;
	protected String apiKey;
	protected String model = "claude-opus-5";
	protected String baseUrl;

	@JsonPropertyDescription("Master switch for the AI config assistant. When false (the default), the Use AI button is hidden and the AdminServer makes no outbound calls.")
	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@JsonPropertyDescription("Anthropic API key. Encrypted at rest by the Configurator ({CLEARTEXT}->{AES}); never sent to the browser.")
	@FormLayout(password = true)
	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	@JsonPropertyDescription("Claude model id used for config generation. Default: claude-opus-5.")
	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	@JsonPropertyDescription("Optional API base URL override, for gateways or private endpoints. Leave empty for the Anthropic API.")
	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}
}
