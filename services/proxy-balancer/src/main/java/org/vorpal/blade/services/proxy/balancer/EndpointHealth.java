package org.vorpal.blade.services.proxy.balancer;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/// The health of one endpoint as THIS node sees it (every node keeps its own
/// independent view — there is no cluster-shared health state). Written by
/// the OPTIONS ping cycle ([OptionsPingCallflow]) and passively by live call
/// legs ([InviteCallflow]): a 2xx marks the endpoint up, a 503 marks it down
/// (honoring Retry-After). Routing consults [#isRoutable].
///
/// Beyond the instantaneous state, each endpoint keeps a bounded ring of
/// recent observations (ping and passive, with OPTIONS round-trip times) and
/// live traffic counters — the raw material for the dashboard's sparklines,
/// heartbeat strips, and traffic-share bars. Health objects survive config
/// publishes for endpoint names that still exist (the name is the registry's
/// stable identity; see ProxyBalancerSettingsManager).
public class EndpointHealth implements Serializable {
	private static final long serialVersionUID = 1L;

	public enum Status {
		up, down
	}

	/// Ring capacity: at the default 60s ping interval this is two hours of
	/// pings; bounded so the health JSON and per-node memory stay flat.
	static final int MAX_SAMPLES = 120;

	/// One observation: a ping verdict or a passive mark from a live leg.
	public static class Sample implements Serializable {
		private static final long serialVersionUID = 1L;

		/// Epoch millis of the observation.
		public final long time;
		public final boolean up;
		/// OPTIONS round-trip in millis; -1 when not measured (passive marks,
		/// and the locally-generated 408 when nothing answered).
		public final int rttMs;
		/// "ping" (OPTIONS cycle) or "call" (live INVITE leg).
		public final String source;
		public final String note;

		Sample(long time, boolean up, int rttMs, String source, String note) {
			this.time = time;
			this.up = up;
			this.rttMs = rttMs;
			this.source = source;
			this.note = note;
		}
	}

	private volatile Status status = Status.up;

	/// Epoch millis until which the endpoint asked to be left alone
	/// (503 Retry-After). Null when no backoff is in force.
	private volatile Long downUntil;

	/// Epoch millis of the last observation (ping or live leg), 0 if never.
	private volatile long lastChecked;

	/// Human-readable cause of the current state, e.g. "OPTIONS 200",
	/// "503 Retry-After 120".
	private volatile String note = "unchecked";

	/// Most recent measured OPTIONS round-trip in millis, -1 before any.
	private volatile int lastRttMs = -1;

	/// Recent observations, oldest first; guarded by itself (writers are the
	/// ping timer thread and SIP worker threads — appends are cheap).
	private final ArrayDeque<Sample> samples = new ArrayDeque<>();

	// Live INVITE traffic on this node (see InviteCallflow.trackHealth):
	// every leg-final response is an attempt; 2xx counts a success;
	// failover-classified failures (408, 480, 404, 5xx, 3xx) count a failover.
	private final AtomicLong attempts = new AtomicLong();
	private final AtomicLong successes = new AtomicLong();
	private final AtomicLong failovers = new AtomicLong();

	/// Should routing offer this endpoint a call right now? A Retry-After
	/// backoff wins until it expires; after expiry the endpoint is probed by
	/// live traffic again (a repeat 503 just renews the backoff).
	public boolean isRoutable(long now) {
		if (downUntil != null) {
			return now >= downUntil;
		}
		return status == Status.up;
	}

	public void markUp(String note, String source, int rttMs) {
		this.status = Status.up;
		this.downUntil = null;
		this.lastChecked = System.currentTimeMillis();
		this.note = note;
		if (rttMs >= 0) {
			this.lastRttMs = rttMs;
		}
		addSample(new Sample(this.lastChecked, true, rttMs, source, note));
	}

	public void markDown(String note, Integer retryAfterSeconds, String source, int rttMs) {
		this.status = Status.down;
		this.lastChecked = System.currentTimeMillis();
		// a plain down CLEARS any old backoff — otherwise a stale, expired
		// downUntil from an earlier 503 would keep isRoutable() true
		this.downUntil = (retryAfterSeconds != null && retryAfterSeconds > 0)
				? this.lastChecked + retryAfterSeconds * 1000L
				: null;
		this.note = note;
		if (rttMs >= 0) {
			this.lastRttMs = rttMs;
		}
		addSample(new Sample(this.lastChecked, false, rttMs, source, note));
	}

	private void addSample(Sample sample) {
		synchronized (samples) {
			samples.addLast(sample);
			while (samples.size() > MAX_SAMPLES) {
				samples.removeFirst();
			}
		}
	}

	/// Copy of the observation ring, oldest first.
	public List<Sample> snapshotSamples() {
		synchronized (samples) {
			return new ArrayList<>(samples);
		}
	}

	public void recordAttempt() {
		attempts.incrementAndGet();
	}

	public void recordSuccess() {
		successes.incrementAndGet();
	}

	public void recordFailover() {
		failovers.incrementAndGet();
	}

	public Status getStatus() {
		return status;
	}

	public Long getDownUntil() {
		return downUntil;
	}

	public long getLastChecked() {
		return lastChecked;
	}

	public String getNote() {
		return note;
	}

	public int getLastRttMs() {
		return lastRttMs;
	}

	public long getAttempts() {
		return attempts.get();
	}

	public long getSuccesses() {
		return successes.get();
	}

	public long getFailovers() {
		return failovers.get();
	}

}
