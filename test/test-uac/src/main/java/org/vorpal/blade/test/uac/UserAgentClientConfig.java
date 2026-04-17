package org.vorpal.blade.test.uac;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Configuration for the BLADE Test UAC.
///
/// On each inbound call from a softphone (the B2BUA outbound leg
/// being constructed), the servlet loads the configured `template`
/// file and applies it to the outbound INVITE: the first line
/// (if present) becomes the outbound Request-URI; subsequent
/// `Name: value` lines become SIP headers; and the body (if any)
/// replaces the outbound content. An empty body leaves the
/// original content alone.
///
/// The remaining load-test fields still control the programmatic
/// [LoadGenerator] pathway.
public class UserAgentClientConfig extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	protected String template;
	protected String fromAddressPattern;
	protected String toAddressPattern;
	protected String requestUriTemplate;
	protected String duration = "30s";

	/// Returns the filename (in `_templates/`) of the SIP-message
	/// template applied to outbound INVITEs on inbound softphone
	/// calls. Format: optional request-line, `Name: value` headers,
	/// blank line, optional body.
	@JsonPropertyDescription("Template filename in _templates/ applied to outbound INVITEs (headers + optional body)")
	public String getTemplate() {
		return template;
	}

	public void setTemplate(String template) {
		this.template = template;
	}

	/// Returns the From address pattern. Supports `${index}` for per-call uniqueness.
	@JsonPropertyDescription("From address pattern (e.g. sip:load-${index}@blade.test)")
	public String getFromAddressPattern() {
		return fromAddressPattern;
	}

	public void setFromAddressPattern(String fromAddressPattern) {
		this.fromAddressPattern = fromAddressPattern;
	}

	/// Returns the To address pattern. Supports `${index}` for per-call uniqueness.
	@JsonPropertyDescription("To address pattern (e.g. sip:target@uas.test)")
	public String getToAddressPattern() {
		return toAddressPattern;
	}

	public void setToAddressPattern(String toAddressPattern) {
		this.toAddressPattern = toAddressPattern;
	}

	/// Returns the request URI template. Supports `${index}` substitution.
	@JsonPropertyDescription("Request URI template (e.g. sip:target@uas.test;status=200;duration=30s)")
	public String getRequestUriTemplate() {
		return requestUriTemplate;
	}

	public void setRequestUriTemplate(String requestUriTemplate) {
		this.requestUriTemplate = requestUriTemplate;
	}

	/// Returns the default call duration before auto-BYE in human-readable format.
	@JsonPropertyDescription("Default call duration before auto-BYE (e.g. 30s, 5m, 1h)")
	public String getDuration() {
		return duration;
	}

	public void setDuration(String duration) {
		this.duration = duration;
	}
}
