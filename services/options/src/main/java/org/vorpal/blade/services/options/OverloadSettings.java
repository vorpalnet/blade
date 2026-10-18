package org.vorpal.blade.services.options;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// When this server refuses new calls, and what it warns about first. Read by
/// [OverloadMonitor] on every sample, so a change applies without a restart.
public class OverloadSettings implements Serializable {
	private static final long serialVersionUID = 1L;

	private int refuseMillis = 100;
	private int gcWarnPercent = 25;
	private int heapWarnPercent = 85;
	private int lockFailuresWarnPerMinute = 10;

	@JsonPropertyDescription("Refuse new calls with 503 Server busy when 90% of this engine's SIP messages over the last second took this many milliseconds or more, from reading each off the socket to finishing with it. Calls already up carry on, and new calls are taken again once that falls below half. A healthy engine takes a few milliseconds; the container measures up to 200. One number for any hardware: a faster engine works its queue off sooner. Takes effect only where blade-overload.jar is in the domain lib folder and sipserver.xml names its collector (./install.sh <env> overload). 0 turns refusing off. Default 100.")
	public int getRefuseMillis() {
		return refuseMillis;
	}

	public void setRefuseMillis(int refuseMillis) {
		this.refuseMillis = clamp(refuseMillis, 0, EngineLatency.CEILING_MILLIS);
	}

	@JsonPropertyDescription("Warn when garbage collection pauses take this percent of the engine's time over the last 10 seconds. An engine that spends a quarter of its time collecting is short of heap, and its messages slow next. 0 turns the warning off. Default 25.")
	public int getGcWarnPercent() {
		return gcWarnPercent;
	}

	public void setGcWarnPercent(int gcWarnPercent) {
		this.gcWarnPercent = clamp(gcWarnPercent, 0, 100);
	}

	@JsonPropertyDescription("Warn when the old generation is still this percent full right after it was collected: memory the collector cannot free, which a raw heap reading cannot show because heap use rises and falls with every collection. 0 turns the warning off. Default 85.")
	public int getHeapWarnPercent() {
		return heapWarnPercent;
	}

	public void setHeapWarnPercent(int heapWarnPercent) {
		this.heapWarnPercent = clamp(heapWarnPercent, 0, 100);
	}

	@JsonPropertyDescription("Warn when this many call-state lock failures happen in a minute. They are the early sign of trouble in the call-state store, whose failures the container answers with 503 Service Unavailable. 0 turns the warning off. Default 10.")
	public int getLockFailuresWarnPerMinute() {
		return lockFailuresWarnPerMinute;
	}

	public void setLockFailuresWarnPerMinute(int lockFailuresWarnPerMinute) {
		this.lockFailuresWarnPerMinute = Math.max(0, lockFailuresWarnPerMinute);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
