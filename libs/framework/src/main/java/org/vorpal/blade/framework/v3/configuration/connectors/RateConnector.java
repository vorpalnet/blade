package org.vorpal.blade.framework.v3.configuration.connectors;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Counts calls per key over a sliding window and writes the count, this call
/// included, to `${variable}`. Keyed on the caller's number it answers "how
/// often has this number called in the last minute", which a routing clause
/// such as `${callRate} > 10` turns into a decision.
///
/// ## Counted on this node only
///
/// Each engine keeps its own counts; nothing is shared across the cluster. With
/// calls spread evenly over N engines a node sees about 1/N of one caller's
/// calls, so set thresholds per node. A shared counter would put a cluster-wide
/// write on every INVITE, and an estimate is enough to catch a dialer.
///
/// ## Bounded
///
/// A flood of calls from random numbers is itself an attack, so the connector
/// tracks at most `maxKeys` keys. When full it first drops keys whose counts
/// have aged out of the window; if none have, a new key is not counted and `${variable}` stays unset
/// for that call, so no rate rule fires on it. Counts restart when the
/// configuration reloads.
///
/// The window is approximated from two fixed buckets, the current and the
/// previous, weighting the previous by how much of it still overlaps the
/// window. Memory per key is three numbers.
@JsonPropertyOrder({ "type", "id", "description", "keyExpression", "variable", "windowSeconds", "maxKeys" })
public class RateConnector extends Connector implements Serializable {
	private static final long serialVersionUID = 1L;

	private String keyExpression;
	private String variable = "callRate";
	private int windowSeconds = 60;
	private int maxKeys = 100_000;

	@JsonIgnore
	private transient ConcurrentHashMap<String, Counter> counters;

	public RateConnector() {
	}

	@JsonPropertyDescription("${var} template naming what to count, e.g. ${ani}")
	public String getKeyExpression() { return keyExpression; }
	public void setKeyExpression(String keyExpression) { this.keyExpression = keyExpression; }

	@JsonPropertyDescription("Context variable that receives the count, this call included. Default callRate")
	public String getVariable() { return variable; }
	public void setVariable(String variable) { this.variable = variable; }

	@JsonPropertyDescription("Length of the sliding window in seconds. Default 60")
	public int getWindowSeconds() { return windowSeconds; }
	public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }

	@JsonPropertyDescription("Most keys tracked on one node; a new key beyond this is not counted. Default 100000")
	public int getMaxKeys() { return maxKeys; }
	public void setMaxKeys(int maxKeys) { this.maxKeys = maxKeys; }

	/// Selectors are meaningless here; the count is written to `variable`.
	@Override
	@JsonIgnore
	public List<Selector> getSelectors() {
		return super.getSelectors();
	}

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		record(ctx, System.currentTimeMillis());
		return CompletableFuture.completedFuture(null);
	}

	/// Counts one call for the resolved key at `now` and stores the windowed
	/// count. Returns the count, or -1 when nothing was counted.
	long record(Context ctx, long now) {
		if (ctx == null || keyExpression == null || variable == null || windowSeconds <= 0) return -1;
		String key = ctx.resolve(keyExpression);
		if (key == null || key.isEmpty() || key.equals(keyExpression) || key.contains("${")) return -1;

		long window = windowSeconds * 1000L;
		Map<String, Counter> map = counters();
		Counter counter = map.get(key);
		if (counter == null) {
			if (map.size() >= maxKeys) evictIdle(map, now, window);
			if (map.size() >= maxKeys) return -1;
			counter = map.computeIfAbsent(key, k -> new Counter());
		}
		long count = counter.hit(now, window);
		ctx.put(variable, Long.toString(count));
		return count;
	}

	int trackedKeys() {
		return counters().size();
	}

	private synchronized Map<String, Counter> counters() {
		if (counters == null) counters = new ConcurrentHashMap<>();
		return counters;
	}

	private static void evictIdle(Map<String, Counter> map, long now, long window) {
		map.values().removeIf(c -> c.idleSince(now, window));
	}

	private static final class Counter {
		private long bucketStart = Long.MIN_VALUE;
		private long current;
		private long previous;

		synchronized long hit(long now, long window) {
			roll(now, window);
			current++;
			double overlap = 1.0 - (double) (now - bucketStart) / window;
			return current + (long) Math.ceil(previous * overlap);
		}

		synchronized boolean idleSince(long now, long window) {
			return bucketStart == Long.MIN_VALUE || now - bucketStart >= 2 * window;
		}

		private void roll(long now, long window) {
			if (bucketStart == Long.MIN_VALUE) {
				bucketStart = now;
				return;
			}
			long elapsed = now - bucketStart;
			if (elapsed >= 2 * window) {
				previous = 0;
				current = 0;
				bucketStart = now;
			} else if (elapsed >= window) {
				previous = current;
				current = 0;
				bucketStart += window;
			}
		}
	}
}
