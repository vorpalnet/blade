package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Defaults for originated (load-generated) calls. A REST or JMX start
/// request can override any field per-run; null/empty request fields fall
/// back to these.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "scenario", "fromAddressPattern", "toAddressPattern", "requestUriTemplate", "duration", "load" })
public class OriginateSettings implements Serializable {
	private static final long serialVersionUID = 1L;

	private String scenario;
	private String fromAddressPattern;
	private String toAddressPattern;
	private String requestUriTemplate;
	private String duration = "30s";
	private LoadSettings load = new LoadSettings();

	@JsonPropertyDescription("Default scenario for originated calls (must name an entry in scenarios with role 'originate').")
	public String getScenario() {
		return scenario;
	}

	public void setScenario(String scenario) {
		this.scenario = scenario;
	}

	@JsonPropertyDescription("From address pattern. Supports ${index} for per-call uniqueness, e.g. sip:load-${index}@blade.test")
	public String getFromAddressPattern() {
		return fromAddressPattern;
	}

	public void setFromAddressPattern(String fromAddressPattern) {
		this.fromAddressPattern = fromAddressPattern;
	}

	@JsonPropertyDescription("To address pattern. Supports ${index}, e.g. sip:target@uas.test")
	public String getToAddressPattern() {
		return toAddressPattern;
	}

	public void setToAddressPattern(String toAddressPattern) {
		this.toAddressPattern = toAddressPattern;
	}

	@JsonPropertyDescription("Request URI template. Supports ${index}, e.g. sip:target@uas.test;status=200")
	public String getRequestUriTemplate() {
		return requestUriTemplate;
	}

	public void setRequestUriTemplate(String requestUriTemplate) {
		this.requestUriTemplate = requestUriTemplate;
	}

	@JsonPropertyDescription("Default call duration before auto-BYE (e.g. 30s, 5m, 1h). A scenario's responseScript.autoByeAfter overrides this.")
	public String getDuration() {
		return duration;
	}

	public void setDuration(String duration) {
		this.duration = duration;
	}

	@JsonPropertyDescription("Default load pacing parameters (mode, rate, caps).")
	public LoadSettings getLoad() {
		return load;
	}

	public void setLoad(LoadSettings load) {
		this.load = load;
	}
}
