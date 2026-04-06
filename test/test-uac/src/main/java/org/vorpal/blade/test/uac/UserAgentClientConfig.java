package org.vorpal.blade.test.uac;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Configuration for the BLADE Test UAC.
///
/// Includes SIP header defaults and load test parameters.
/// Load test fields provide defaults that can be overridden per-test
/// via the LoadTestRequest REST API.
public class UserAgentClientConfig extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;
	protected Map<String, String> headers = new HashMap<>();
	protected String fromAddressPattern;
	protected String toAddressPattern;
	protected String requestUriTemplate;
	protected String duration = "30s";
	protected String sdpContent;

	/// Returns custom SIP headers applied to generated INVITE requests.
	@JsonPropertyDescription("Custom SIP headers for INVITE requests")
	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
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

	/// Returns the SDP content for INVITE requests, or null for no SDP.
	@JsonPropertyDescription("SDP body for INVITE requests (null for no SDP)")
	public String getSdpContent() {
		return sdpContent;
	}

	public void setSdpContent(String sdpContent) {
		this.sdpContent = sdpContent;
	}

}
