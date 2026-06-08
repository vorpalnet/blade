package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/// Request model for starting a load run, via REST or the [TesterMXBean].
/// Null fields fall back to the configuration's `originate` defaults
/// ([OriginateSettings] / [LoadSettings]).
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoadRequest implements Serializable {
	private static final long serialVersionUID = 1L;

	private String mode;
	private Double targetCps;
	private Integer targetConcurrent;
	private Integer maxCalls;
	private String scenario;
	private String fromAddressPattern;
	private String toAddressPattern;
	private String requestUriTemplate;
	private String duration;
	private Map<String, String> headers;

	/// Load generation mode override: "cps" or "concurrent".
	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	/// Target calls per second override (cps mode).
	public Double getTargetCps() {
		return targetCps;
	}

	public void setTargetCps(Double targetCps) {
		this.targetCps = targetCps;
	}

	/// Target concurrent call count override (concurrent mode).
	public Integer getTargetConcurrent() {
		return targetConcurrent;
	}

	public void setTargetConcurrent(Integer targetConcurrent) {
		this.targetConcurrent = targetConcurrent;
	}

	/// Maximum calls to generate override (0 = unlimited).
	public Integer getMaxCalls() {
		return maxCalls;
	}

	public void setMaxCalls(Integer maxCalls) {
		this.maxCalls = maxCalls;
	}

	/// Scenario to run. Overrides the configuration's `originate.scenario`.
	public String getScenario() {
		return scenario;
	}

	public void setScenario(String scenario) {
		this.scenario = scenario;
	}

	/// From address pattern override. Supports `${index}`.
	public String getFromAddressPattern() {
		return fromAddressPattern;
	}

	public void setFromAddressPattern(String fromAddressPattern) {
		this.fromAddressPattern = fromAddressPattern;
	}

	/// To address pattern override. Supports `${index}`.
	public String getToAddressPattern() {
		return toAddressPattern;
	}

	public void setToAddressPattern(String toAddressPattern) {
		this.toAddressPattern = toAddressPattern;
	}

	/// Request URI template override.
	public String getRequestUriTemplate() {
		return requestUriTemplate;
	}

	public void setRequestUriTemplate(String requestUriTemplate) {
		this.requestUriTemplate = requestUriTemplate;
	}

	/// Call duration override (e.g. "30s", "5m").
	public String getDuration() {
		return duration;
	}

	public void setDuration(String duration) {
		this.duration = duration;
	}

	/// Custom SIP headers applied to every generated INVITE.
	public Map<String, String> getHeaders() {
		return headers;
	}

	public void setHeaders(Map<String, String> headers) {
		this.headers = headers;
	}
}
