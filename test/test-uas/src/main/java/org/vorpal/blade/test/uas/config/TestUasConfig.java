package org.vorpal.blade.test.uas.config;

import java.util.HashMap;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.vorpal.blade.framework.v2.config.Configuration;

/// Configuration for the BLADE Test UAS.
///
/// Controls default response behavior for incoming calls. All defaults
/// can be overridden per-call via SIP request URI parameters:
/// `?status=503&delay=5s&duration=60s`
public class TestUasConfig extends Configuration {

	protected HashMap<String, Integer> errorMap = new HashMap<>();
	protected int defaultStatus = 200;
	protected String defaultDelay = "0s";
	protected String defaultDuration = "30s";
	protected String sdpContent;
	protected String template;

	public TestUasConfig() {
	}

	/// Returns the filename (in `_templates/`) of the SIP-message
	/// template applied to the outbound leg when test-uas forwards
	/// a call (B2BUA mode). Format: optional request-line, then
	/// `Name: value` headers, blank line, optional body.
	///
	/// For test-uas this is typically headers-only — the multipart
	/// body on the inbound leg is stripped to its SDP part before
	/// being sent on to the softphone.
	@JsonPropertyDescription("Template filename in _templates/ applied to the B2BUA outbound INVITE (headers; body optional)")
	public String getTemplate() {
		return template;
	}

	public void setTemplate(String template) {
		this.template = template;
	}

	/// Returns the error map that maps phone numbers to SIP error codes.
	@JsonPropertyDescription("Maps called phone numbers to SIP error status codes (e.g. 18165550404 -> 404)")
	public HashMap<String, Integer> getErrorMap() {
		return errorMap;
	}

	public void setErrorMap(HashMap<String, Integer> errorMap) {
		this.errorMap = errorMap;
	}

	/// Returns the default SIP response status code.
	@JsonPropertyDescription("Default SIP response status code (e.g. 200, 503)")
	public int getDefaultStatus() {
		return defaultStatus;
	}

	public void setDefaultStatus(int defaultStatus) {
		this.defaultStatus = defaultStatus;
	}

	/// Returns the default response delay in human-readable format (e.g. "5s", "100ms").
	@JsonPropertyDescription("Default response delay before sending reply (e.g. 0s, 5s, 100ms)")
	public String getDefaultDelay() {
		return defaultDelay;
	}

	public void setDefaultDelay(String defaultDelay) {
		this.defaultDelay = defaultDelay;
	}

	/// Returns the default call duration before auto-BYE in human-readable format.
	@JsonPropertyDescription("Default call duration before auto-BYE (e.g. 30s, 5m, 1h)")
	public String getDefaultDuration() {
		return defaultDuration;
	}

	public void setDefaultDuration(String defaultDuration) {
		this.defaultDuration = defaultDuration;
	}

	/// Returns the SDP content to include in 2xx responses, or null for the built-in default.
	@JsonPropertyDescription("SDP body for 2xx responses (null uses built-in default)")
	public String getSdpContent() {
		return sdpContent;
	}

	public void setSdpContent(String sdpContent) {
		this.sdpContent = sdpContent;
	}

	/// Returns the default delay parsed as seconds.
	@JsonIgnore
	public int getDefaultDelaySeconds() {
		try {
			return parseHRDurationAsSeconds(defaultDelay);
		} catch (Exception e) {
			return 0;
		}
	}

	/// Returns the default duration parsed as seconds.
	@JsonIgnore
	public int getDefaultDurationSeconds() {
		try {
			return parseHRDurationAsSeconds(defaultDuration);
		} catch (Exception e) {
			return 30;
		}
	}

}
