package org.vorpal.blade.framework.v3.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/// A latency distribution recorded into **fixed buckets**, so distributions from
/// different nodes can be added together.
///
/// **Why fixed buckets and not a sampled percentile.** Percentiles do not merge.
/// The Test Console already documents the consequence — *"counters summed across
/// nodes; latency columns show the worst node, since percentiles don't merge"* —
/// which is a fair compromise for a load test and a permanent wart in a
/// cluster-wide chart. Because every node uses the same boundaries, the admin
/// tier can sum bucket arrays element-wise and compute a true cluster p95 from
/// the total. That property is why the bucket array is published in the report
/// rather than the percentiles alone.
///
/// **This is a one-way door.** Once nodes are reporting a boundary set, changing
/// it invalidates any stored history, because old and new arrays no longer line
/// up. The default mirrors the boundaries `ScenarioStats` already uses in the
/// tester, so the two agree.
///
/// **Cost.** Recording is a short linear scan over a small ascending array (a
/// branch-predictable comparison per boundary) and one [LongAdder] increment.
/// Anything that keeps samples in a list and sorts to compute a percentile would
/// not be viable on a call path; this is.
public final class Histogram {

	/// Default boundaries in milliseconds, matching `ScenarioStats.BOUNDS_MS`.
	/// A value is counted in the first bucket whose boundary it does not exceed;
	/// anything larger lands in a final overflow bucket.
	public static final long[] DEFAULT_BOUNDS_MS = { 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000 };

	private final String name;
	private final String description;
	private final long[] bounds;
	private final LongAdder[] buckets;
	private final LongAdder count = new LongAdder();
	private final LongAdder sum = new LongAdder();
	private final AtomicLong max = new AtomicLong();

	Histogram(String name, String description) {
		this(name, description, DEFAULT_BOUNDS_MS);
	}

	/// @param bounds ascending boundaries; the array is copied, and one extra
	///               overflow bucket is added past the last boundary
	Histogram(String name, String description, long[] bounds) {
		if (bounds == null || bounds.length == 0) {
			throw new IllegalArgumentException("histogram '" + name + "' needs at least one boundary");
		}
		for (int i = 1; i < bounds.length; i++) {
			if (bounds[i] <= bounds[i - 1]) {
				throw new IllegalArgumentException(
						"histogram '" + name + "' boundaries must ascend; " + bounds[i] + " follows " + bounds[i - 1]);
			}
		}
		this.name = name;
		this.description = description;
		this.bounds = bounds.clone();
		this.buckets = new LongAdder[this.bounds.length + 1];
		for (int i = 0; i < buckets.length; i++) {
			buckets[i] = new LongAdder();
		}
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	/// Record one observation, in milliseconds. Negative values are ignored
	/// rather than thrown: a clock going backwards should not take a call down.
	public void record(long millis) {
		if (millis < 0) {
			return;
		}
		buckets[bucketOf(millis)].increment();
		count.increment();
		sum.add(millis);
		updateMax(millis);
	}

	private int bucketOf(long millis) {
		for (int i = 0; i < bounds.length; i++) {
			if (millis <= bounds[i]) {
				return i;
			}
		}
		return bounds.length;
	}

	private void updateMax(long millis) {
		long current = max.get();
		while (millis > current && !max.compareAndSet(current, millis)) {
			current = max.get();
		}
	}

	/// Number of observations recorded.
	public long count() {
		return count.sum();
	}

	/// Largest observation seen since the last reset.
	public long maxMillis() {
		return max.get();
	}

	/// Interpolation-free percentile: the boundary of the bucket the requested
	/// quantile falls into. Reported per node for convenience; the cluster-wide
	/// figure is computed by the admin tier from the summed buckets.
	public long percentileMillis(double quantile) {
		long total = count.sum();
		if (total == 0) {
			return 0;
		}
		long target = (long) Math.ceil(quantile * total);
		long running = 0;
		for (int i = 0; i < buckets.length; i++) {
			running += buckets[i].sum();
			if (running >= target) {
				return (i < bounds.length) ? bounds[i] : max.get();
			}
		}
		return max.get();
	}

	/// Drop every bucket, the count, the sum and the maximum.
	public void reset() {
		for (LongAdder bucket : buckets) {
			bucket.reset();
		}
		count.reset();
		sum.reset();
		max.set(0);
	}

	/// A snapshot carrying the raw bucket array — the part that makes cluster
	/// aggregation correct rather than approximate.
	public HistogramReport report() {
		HistogramReport report = new HistogramReport();
		report.setName(name);
		report.setDescription(description);
		long total = count.sum();
		report.setCount(total);
		report.setMaxMillis(max.get());
		report.setMeanMillis(total == 0 ? 0 : sum.sum() / total);
		report.setBoundsMillis(bounds.clone());
		long[] counts = new long[buckets.length];
		for (int i = 0; i < buckets.length; i++) {
			counts[i] = buckets[i].sum();
		}
		report.setBucketCounts(counts);
		report.setP50Millis(percentileMillis(0.50));
		report.setP90Millis(percentileMillis(0.90));
		report.setP99Millis(percentileMillis(0.99));
		return report;
	}
}
