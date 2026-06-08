package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Load pacing defaults for the [LoadEngine].
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "mode", "targetCps", "targetConcurrent", "maxCalls" })
public class LoadSettings implements Serializable {
	private static final long serialVersionUID = 1L;

	private String mode = "cps";
	private double targetCps = 1.0;
	private int targetConcurrent = 1;
	private int maxCalls = 0;

	@JsonPropertyDescription("Load generation mode: 'cps' (calls per second) or 'concurrent' (maintain N active calls).")
	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	@JsonPropertyDescription("Target calls per second (cps mode).")
	public double getTargetCps() {
		return targetCps;
	}

	public void setTargetCps(double targetCps) {
		this.targetCps = targetCps;
	}

	@JsonPropertyDescription("Target concurrent call count (concurrent mode).")
	public int getTargetConcurrent() {
		return targetConcurrent;
	}

	public void setTargetConcurrent(int targetConcurrent) {
		this.targetConcurrent = targetConcurrent;
	}

	@JsonPropertyDescription("Maximum calls to generate before auto-stop (0 = unlimited).")
	public int getMaxCalls() {
		return maxCalls;
	}

	public void setMaxCalls(int maxCalls) {
		this.maxCalls = maxCalls;
	}
}
