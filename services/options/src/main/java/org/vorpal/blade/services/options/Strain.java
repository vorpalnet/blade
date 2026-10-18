package org.vorpal.blade.services.options;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;

/// The causes behind a filling queue, read so the dashboard can name one
/// before the queue fills: the share of time the JVM spends collecting
/// garbage, how full the old generation stays after a collection, and the
/// rate of call-state lock failures. Each is a warning, never a reason to
/// refuse calls; [OverloadVerdict] refuses on latency alone.
///
/// Nothing here throws. An unreadable signal reads -1.
final class Strain {

	/// Running totals over time, for a rate over a trailing window.
	static final class Totals {
		private final ArrayDeque<long[]> points = new ArrayDeque<>();

		/// Records `total` at `now` and returns how much it grew over the
		/// trailing `windowMillis` (from the oldest point inside it), or -1
		/// until there are two points. Points older than the window go.
		long growth(long now, long total, long windowMillis) {
			points.addLast(new long[] { now, total });
			while (points.size() > 2 && points.peekFirst()[0] < now - windowMillis) {
				points.removeFirst();
			}
			if (points.size() < 2) {
				return -1;
			}
			long[] first = points.peekFirst();
			return Math.max(0, total - first[1]);
		}

		/// Milliseconds between the oldest and newest point.
		long span() {
			return points.size() < 2 ? 0 : points.peekLast()[0] - points.peekFirst()[0];
		}
	}

	private final Totals gc = new Totals();
	private final Totals locks = new Totals();

	/// Percent of wall time spent in collection pauses over the last
	/// `windowSeconds`, or -1 until a second reading.
	///
	/// Counts the collectors that pause the application. A concurrent
	/// collector's bean (G1's `G1 Concurrent GC`, ZGC's `ZGC Cycles`) reports
	/// time spent beside the application, which would read as a server
	/// collecting all the time when it is not.
	int gcPercent(long now, int windowSeconds) {
		long total = 0;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			String name = bean.getName();
			if (name.contains("Concurrent") || name.contains("Cycles")) {
				continue;
			}
			long t = bean.getCollectionTime();
			if (t > 0) {
				total += t;
			}
		}
		long grew = gc.growth(now, total, windowSeconds * 1000L);
		long span = gc.span();
		return percentOf(grew, span);
	}

	static int percentOf(long part, long whole) {
		if (part < 0 || whole <= 0) {
			return -1;
		}
		return (int) Math.min(100, part * 100L / whole);
	}

	/// How full the old generation was right after its last collection, as a
	/// percent of its maximum, or -1 when no such pool reports it.
	static int heapAfterGcPercent() {
		int worst = -1;
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() != MemoryType.HEAP || !isOld(pool.getName())) {
				continue;
			}
			MemoryUsage after = pool.getCollectionUsage();
			if (after == null) {
				continue;
			}
			long max = after.getMax() > 0 ? after.getMax() : pool.getUsage().getMax();
			worst = Math.max(worst, percentOf(after.getUsed(), max));
		}
		return worst;
	}

	static boolean isOld(String pool) {
		return pool.contains("Old") || pool.contains("Tenured");
	}

	/// Call-state lock failures in the last minute, from the container's
	/// `SipPerformanceRuntime` counter, or -1 while unreadable or before a
	/// second reading.
	long lockFailuresPerMinute(long now) {
		Object v = SipPerformance.read("LockFailures");
		if (!(v instanceof Number)) {
			return -1;
		}
		long grew = locks.growth(now, ((Number) v).longValue(), 60_000L);
		long span = locks.span();
		if (grew < 0 || span <= 0) {
			return -1;
		}
		return span >= 60_000L ? grew : grew * 60_000L / span;
	}
}
