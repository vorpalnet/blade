package org.vorpal.blade.test.uac;

import org.vorpal.blade.framework.v3.tester.TesterConfiguration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Configuration for the BLADE Test UAC. All app-specific settings —
/// scenarios, rule sets, originate/load defaults, scenario selection — come
/// from the inherited [TesterConfiguration].
///
/// The pre-scenario top-level fields are still accepted so existing
/// `test-uac.json` files keep loading: `template` remains functional (it
/// applies to every outbound softphone INVITE when the resolved scenario has
/// no template of its own), and `fromAddressPattern` / `toAddressPattern` /
/// `requestUriTemplate` / `duration` feed straight into `originate` and
/// never serialize back out.
public class UserAgentClientConfig extends TesterConfiguration {
	private static final long serialVersionUID = 1L;

	protected String template;

	public UserAgentClientConfig() {
	}

	/// Deprecated in favor of a scenario `template`; still honored for
	/// existing configs.
	@JsonPropertyDescription("Deprecated — prefer a scenario's template. Filename in _templates/ applied to outbound softphone INVITEs when the resolved scenario has no template.")
	public String getTemplate() {
		return template;
	}

	public void setTemplate(String template) {
		this.template = template;
	}

	// ------------------------------------------------------------
	// Legacy top-level fields → originate.* (load-only aliases; no
	// getters, so they never serialize back out)
	// ------------------------------------------------------------

	@JsonProperty("fromAddressPattern")
	public void setFromAddressPattern(String fromAddressPattern) {
		getOriginate().setFromAddressPattern(fromAddressPattern);
	}

	@JsonProperty("toAddressPattern")
	public void setToAddressPattern(String toAddressPattern) {
		getOriginate().setToAddressPattern(toAddressPattern);
	}

	@JsonProperty("requestUriTemplate")
	public void setRequestUriTemplate(String requestUriTemplate) {
		getOriginate().setRequestUriTemplate(requestUriTemplate);
	}

	@JsonProperty("duration")
	public void setDuration(String duration) {
		getOriginate().setDuration(duration);
	}
}
