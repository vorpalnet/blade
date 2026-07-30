package org.vorpal.blade.framework.v3.metrics;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// JSON snapshot of one [Histogram], carried in a [MetricsReport].
///
/// [#getBoundsMillis] and [#getBucketCounts] are the load-bearing fields: every
/// node publishes the same boundaries, so the admin tier sums the bucket arrays
/// element-wise and computes a true cluster-wide percentile. The per-node
/// percentiles below are a convenience — they are **not** mergeable and must not
/// be averaged across nodes.
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "name", "description", "count", "meanMillis", "maxMillis", "p50Millis", "p90Millis", "p99Millis",
		"boundsMillis", "bucketCounts" })
public class HistogramReport implements Serializable {

	private static final long serialVersionUID = 1L;

	private String name;
	private String description;
	private long count;
	private long meanMillis;
	private long maxMillis;
	private long p50Millis;
	private long p90Millis;
	private long p99Millis;
	private long[] boundsMillis;
	private long[] bucketCounts;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public long getCount() {
		return count;
	}

	public void setCount(long count) {
		this.count = count;
	}

	public long getMeanMillis() {
		return meanMillis;
	}

	public void setMeanMillis(long meanMillis) {
		this.meanMillis = meanMillis;
	}

	public long getMaxMillis() {
		return maxMillis;
	}

	public void setMaxMillis(long maxMillis) {
		this.maxMillis = maxMillis;
	}

	/// Per-node p50. Not mergeable — see the class note.
	public long getP50Millis() {
		return p50Millis;
	}

	public void setP50Millis(long p50Millis) {
		this.p50Millis = p50Millis;
	}

	/// Per-node p90. Not mergeable — see the class note.
	public long getP90Millis() {
		return p90Millis;
	}

	public void setP90Millis(long p90Millis) {
		this.p90Millis = p90Millis;
	}

	/// Per-node p99. Not mergeable — see the class note.
	public long getP99Millis() {
		return p99Millis;
	}

	public void setP99Millis(long p99Millis) {
		this.p99Millis = p99Millis;
	}

	/// Ascending bucket boundaries. Identical on every node reporting this
	/// metric, which is what makes [#getBucketCounts] addable.
	public long[] getBoundsMillis() {
		return boundsMillis;
	}

	public void setBoundsMillis(long[] boundsMillis) {
		this.boundsMillis = boundsMillis;
	}

	/// One count per bucket, plus a final overflow bucket past the last
	/// boundary — so its length is always `boundsMillis.length + 1`.
	public long[] getBucketCounts() {
		return bucketCounts;
	}

	public void setBucketCounts(long[] bucketCounts) {
		this.bucketCounts = bucketCounts;
	}
}
