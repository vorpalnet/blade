package org.vorpal.blade.test.uac;

import java.io.Serializable;

/// Status model returned by the load test REST API.
public class LoadTestStatus implements Serializable {

	private static final long serialVersionUID = 1L;

	private String state;
	private String mode;
	private double targetCps;
	private int targetConcurrent;
	private int activeCalls;
	private long totalStarted;
	private long totalCompleted;
	private long totalFailed;
	private long elapsedMilliseconds;

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getMode() {
		return mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public double getTargetCps() {
		return targetCps;
	}

	public void setTargetCps(double targetCps) {
		this.targetCps = targetCps;
	}

	public int getTargetConcurrent() {
		return targetConcurrent;
	}

	public void setTargetConcurrent(int targetConcurrent) {
		this.targetConcurrent = targetConcurrent;
	}

	public int getActiveCalls() {
		return activeCalls;
	}

	public void setActiveCalls(int activeCalls) {
		this.activeCalls = activeCalls;
	}

	public long getTotalStarted() {
		return totalStarted;
	}

	public void setTotalStarted(long totalStarted) {
		this.totalStarted = totalStarted;
	}

	public long getTotalCompleted() {
		return totalCompleted;
	}

	public void setTotalCompleted(long totalCompleted) {
		this.totalCompleted = totalCompleted;
	}

	public long getTotalFailed() {
		return totalFailed;
	}

	public void setTotalFailed(long totalFailed) {
		this.totalFailed = totalFailed;
	}

	public long getElapsedMilliseconds() {
		return elapsedMilliseconds;
	}

	public void setElapsedMilliseconds(long elapsedMilliseconds) {
		this.elapsedMilliseconds = elapsedMilliseconds;
	}

}
