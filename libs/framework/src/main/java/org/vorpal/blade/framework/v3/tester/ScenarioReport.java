package org.vorpal.blade.framework.v3.tester;

import java.io.Serializable;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// JSON-friendly snapshot of one scenario's [ScenarioStats], returned by the
/// REST report endpoint and the [TesterMXBean].
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "scenario", "started", "completed", "failed", "answered", "forwarded", "expectMismatched",
		"assertionsPassed", "assertionsFailed", "assertionsWarned", "finalStatusCounts", "latencyCount",
		"latencyAvgMs", "latencyMaxMs", "latencyP50Ms", "latencyP90Ms", "latencyP99Ms" })
public class ScenarioReport implements Serializable {
	private static final long serialVersionUID = 1L;

	private String scenario;
	private long started;
	private long completed;
	private long failed;
	private long answered;
	private long forwarded;
	private long expectMismatched;
	private long assertionsPassed;
	private long assertionsFailed;
	private long assertionsWarned;
	private Map<String, Long> finalStatusCounts;
	private long latencyCount;
	private long latencyAvgMs;
	private long latencyMaxMs;
	private long latencyP50Ms;
	private long latencyP90Ms;
	private long latencyP99Ms;

	public String getScenario() {
		return scenario;
	}

	public void setScenario(String scenario) {
		this.scenario = scenario;
	}

	public long getStarted() {
		return started;
	}

	public void setStarted(long started) {
		this.started = started;
	}

	public long getCompleted() {
		return completed;
	}

	public void setCompleted(long completed) {
		this.completed = completed;
	}

	public long getFailed() {
		return failed;
	}

	public void setFailed(long failed) {
		this.failed = failed;
	}

	public long getAnswered() {
		return answered;
	}

	public void setAnswered(long answered) {
		this.answered = answered;
	}

	public long getForwarded() {
		return forwarded;
	}

	public void setForwarded(long forwarded) {
		this.forwarded = forwarded;
	}

	public long getExpectMismatched() {
		return expectMismatched;
	}

	public void setExpectMismatched(long expectMismatched) {
		this.expectMismatched = expectMismatched;
	}

	public long getAssertionsPassed() {
		return assertionsPassed;
	}

	public void setAssertionsPassed(long assertionsPassed) {
		this.assertionsPassed = assertionsPassed;
	}

	public long getAssertionsFailed() {
		return assertionsFailed;
	}

	public void setAssertionsFailed(long assertionsFailed) {
		this.assertionsFailed = assertionsFailed;
	}

	public long getAssertionsWarned() {
		return assertionsWarned;
	}

	public void setAssertionsWarned(long assertionsWarned) {
		this.assertionsWarned = assertionsWarned;
	}

	public Map<String, Long> getFinalStatusCounts() {
		return finalStatusCounts;
	}

	public void setFinalStatusCounts(Map<String, Long> finalStatusCounts) {
		this.finalStatusCounts = finalStatusCounts;
	}

	public long getLatencyCount() {
		return latencyCount;
	}

	public void setLatencyCount(long latencyCount) {
		this.latencyCount = latencyCount;
	}

	public long getLatencyAvgMs() {
		return latencyAvgMs;
	}

	public void setLatencyAvgMs(long latencyAvgMs) {
		this.latencyAvgMs = latencyAvgMs;
	}

	public long getLatencyMaxMs() {
		return latencyMaxMs;
	}

	public void setLatencyMaxMs(long latencyMaxMs) {
		this.latencyMaxMs = latencyMaxMs;
	}

	public long getLatencyP50Ms() {
		return latencyP50Ms;
	}

	public void setLatencyP50Ms(long latencyP50Ms) {
		this.latencyP50Ms = latencyP50Ms;
	}

	public long getLatencyP90Ms() {
		return latencyP90Ms;
	}

	public void setLatencyP90Ms(long latencyP90Ms) {
		this.latencyP90Ms = latencyP90Ms;
	}

	public long getLatencyP99Ms() {
		return latencyP99Ms;
	}

	public void setLatencyP99Ms(long latencyP99Ms) {
		this.latencyP99Ms = latencyP99Ms;
	}
}
