package org.vorpal.blade.test.uac;

import java.io.Serializable;
import java.util.Map;

/// Request model for starting a load test.
///
/// Fields override the defaults in UserAgentClientConfig.
/// Null or empty fields fall back to config values.
public class LoadTestRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private String mode = "cps";
	private double targetCps = 1.0;
	private int targetConcurrent = 1;
	private int maxCalls = 0;
	private String fromAddressPattern;
	private String toAddressPattern;
	private String requestUriTemplate;
	private String duration;
	private Map<String, String> headers;

	/// Returns the load generation mode: "cps" or "concurrent".
	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	/// Returns the target calls per second (CPS mode).
	public double getTargetCps() {
		return targetCps;
	}

	public void setTargetCps(double targetCps) {
		this.targetCps = targetCps;
	}

	/// Returns the target concurrent call count (concurrent mode).
	public int getTargetConcurrent() {
		return targetConcurrent;
	}

	public void setTargetConcurrent(int targetConcurrent) {
		this.targetConcurrent = targetConcurrent;
	}

	/// Returns the maximum number of calls to generate (0 = unlimited).
	public int getMaxCalls() {
		return maxCalls;
	}

	public void setMaxCalls(int maxCalls) {
		this.maxCalls = maxCalls;
	}

	/// Returns the From address pattern override. Supports `${index}`.
	public String getFromAddressPattern() {
		return fromAddressPattern;
	}

	public void setFromAddressPattern(String fromAddressPattern) {
		this.fromAddressPattern = fromAddressPattern;
	}

	/// Returns the To address pattern override. Supports `${index}`.
	public String getToAddressPattern() {
		return toAddressPattern;
	}

	public void setToAddressPattern(String toAddressPattern) {
		this.toAddressPattern = toAddressPattern;
	}

	/// Returns the request URI template override.
	public String getRequestUriTemplate() {
		return requestUriTemplate;
	}

	public void setRequestUriTemplate(String requestUriTemplate) {
		this.requestUriTemplate = requestUriTemplate;
	}

	/// Returns the call duration override (e.g. "30s", "5m").
	public String getDuration() {
		return duration;
	}

	public void setDuration(String duration) {
		this.duration = duration;
	}

	/// Returns custom SIP header overrides (merged with config defaults).
	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}

}
