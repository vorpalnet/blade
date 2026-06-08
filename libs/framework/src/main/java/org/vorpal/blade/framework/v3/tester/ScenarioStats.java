package org.vorpal.blade.framework.v3.tester;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/// Lock-free per-scenario counters for one node. All mutators are safe to
/// call from SIP container threads at full load; reads are approximate
/// snapshots, which is fine for a dashboard.
///
/// Latency is tracked in fixed log-scale buckets — cheap, allocation-free,
/// and good enough for p50/p90/p99 on a test tool.
public class ScenarioStats {

	/// Upper bounds (ms) of the latency buckets; the final bucket is
	/// everything above the last bound.
	static final long[] BOUNDS_MS = { 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000 };

	private final LongAdder started = new LongAdder();
	private final LongAdder completed = new LongAdder();
	private final LongAdder failed = new LongAdder();
	private final LongAdder answered = new LongAdder();
	private final LongAdder forwarded = new LongAdder();
	private final LongAdder expectMismatched = new LongAdder();
	private final LongAdder assertionsPassed = new LongAdder();
	private final LongAdder assertionsFailed = new LongAdder();
	private final LongAdder assertionsWarned = new LongAdder();

	private final ConcurrentHashMap<Integer, LongAdder> finalStatus = new ConcurrentHashMap<>();

	private final AtomicLong[] latencyBuckets;
	private final LongAdder latencyCount = new LongAdder();
	private final LongAdder latencySumMs = new LongAdder();
	private final AtomicLong latencyMaxMs = new AtomicLong();

	public ScenarioStats() {
		latencyBuckets = new AtomicLong[BOUNDS_MS.length + 1];
		for (int i = 0; i < latencyBuckets.length; i++) {
			latencyBuckets[i] = new AtomicLong();
		}
	}

	/// An originated call left the engine.
	public void recordStarted() {
		started.increment();
	}

	/// An originated call ended normally (BYE).
	public void recordCompleted() {
		completed.increment();
	}

	/// An originated call failed (error final, exception, or decline).
	public void recordFailed() {
		failed.increment();
	}

	/// An answer-role scenario sent its final response.
	public void recordAnswered(int status) {
		answered.increment();
		countStatus(status);
	}

	/// A b2bua-role scenario forwarded a call and saw its final response.
	public void recordForwarded(int status) {
		forwarded.increment();
		countStatus(status);
	}

	/// An originated call got its final response after `setupMs`.
	public void recordFinal(int status, long setupMs) {
		countStatus(status);
		latencyCount.increment();
		latencySumMs.add(setupMs);
		latencyMaxMs.accumulateAndGet(setupMs, Math::max);
		latencyBuckets[bucketIndex(setupMs)].incrementAndGet();
	}

	/// The final response didn't match the scenario's `expectFinal` filter.
	public void recordExpectMismatch() {
		expectMismatched.increment();
	}

	public void recordAssertionPassed() {
		assertionsPassed.increment();
	}

	public void recordAssertionFailed() {
		assertionsFailed.increment();
	}

	public void recordAssertionWarned() {
		assertionsWarned.increment();
	}

	private void countStatus(int status) {
		finalStatus.computeIfAbsent(status, k -> new LongAdder()).increment();
	}

	private static int bucketIndex(long ms) {
		for (int i = 0; i < BOUNDS_MS.length; i++) {
			if (ms <= BOUNDS_MS[i]) return i;
		}
		return BOUNDS_MS.length;
	}

	/// Approximate quantile from the bucket histogram: the upper bound of
	/// the bucket where the cumulative count crosses `q`. The overflow
	/// bucket reports the observed max.
	long percentileMs(double q) {
		long total = 0;
		for (AtomicLong b : latencyBuckets) total += b.get();
		if (total == 0) return 0;

		long threshold = (long) Math.ceil(q * total);
		long cumulative = 0;
		for (int i = 0; i < latencyBuckets.length; i++) {
			cumulative += latencyBuckets[i].get();
			if (cumulative >= threshold) {
				return (i < BOUNDS_MS.length) ? BOUNDS_MS[i] : latencyMaxMs.get();
			}
		}
		return latencyMaxMs.get();
	}

	/// Snapshot into a JSON-friendly report.
	public ScenarioReport report(String scenarioName) {
		ScenarioReport r = new ScenarioReport();
		r.setScenario(scenarioName);
		r.setStarted(started.sum());
		r.setCompleted(completed.sum());
		r.setFailed(failed.sum());
		r.setAnswered(answered.sum());
		r.setForwarded(forwarded.sum());
		r.setExpectMismatched(expectMismatched.sum());
		r.setAssertionsPassed(assertionsPassed.sum());
		r.setAssertionsFailed(assertionsFailed.sum());
		r.setAssertionsWarned(assertionsWarned.sum());

		Map<String, Long> statusCounts = new TreeMap<>();
		finalStatus.forEach((status, count) -> statusCounts.put(String.valueOf(status), count.sum()));
		r.setFinalStatusCounts(statusCounts);

		long count = latencyCount.sum();
		r.setLatencyCount(count);
		r.setLatencyAvgMs(count > 0 ? latencySumMs.sum() / count : 0);
		r.setLatencyMaxMs(latencyMaxMs.get());
		r.setLatencyP50Ms(percentileMs(0.50));
		r.setLatencyP90Ms(percentileMs(0.90));
		r.setLatencyP99Ms(percentileMs(0.99));
		return r;
	}
}
