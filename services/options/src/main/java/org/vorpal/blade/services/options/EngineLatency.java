package org.vorpal.blade.services.options;

import java.util.ArrayDeque;

/// How long this engine takes over a SIP message, as the container measures
/// it: from reading the message off the socket to finishing with it, so the
/// wait in the SIP queue, the call-state lock and the application's own time
/// all count. The container keeps a running histogram of it in
/// `SipPerformanceRuntime.LatencyCounts`, one count per 10 ms up to 200 ms
/// and a last count for 200 ms or more.
///
/// Each sample takes the difference from the previous one, keeps the last
/// [#WINDOW] differences (one second at the monitor's pace), and reports the
/// 90th percentile over them. One number that means the same on any hardware:
/// a faster engine works a deeper queue off in the same time, so nothing here
/// needs tuning to the box.
final class EngineLatency {

	/// Milliseconds each histogram count covers.
	static final int BUCKET_MILLIS = 10;

	/// The histogram's last count holds everything from here up.
	static final int CEILING_MILLIS = 200;

	/// Samples in the window: one second at [OverloadMonitor#SAMPLE_MILLIS].
	static final int WINDOW = 4;

	static final int PERCENTILE = 90;

	private int[] last;
	private final ArrayDeque<int[]> window = new ArrayDeque<>();
	private boolean readable;

	/// Reads the container's histogram and returns the 90th percentile over
	/// the last second in milliseconds, or -1 when nothing finished in it or
	/// the histogram cannot be read ([#readable] tells which).
	int sample() {
		Object counts = SipPerformance.read("LatencyCounts");
		readable = counts instanceof String[];
		return readable ? sample((String[]) counts) : -1;
	}

	/// Whether the last [#sample()] could read the histogram.
	boolean readable() {
		return readable;
	}

	/// As [#sample()], from the histogram's running counts as the MBean gives
	/// them. Counts are subtracted as `int`, so a counter that wraps past
	/// 2^31 still gives the right difference.
	int sample(String[] cumulative) {
		int[] now = new int[cumulative.length];
		try {
			for (int i = 0; i < now.length; i++) {
				now[i] = Integer.parseInt(cumulative[i].trim());
			}
		} catch (NumberFormatException e) {
			return -1;
		}
		if (last != null && last.length == now.length) {
			int[] delta = new int[now.length];
			for (int i = 0; i < now.length; i++) {
				delta[i] = Math.max(0, now[i] - last[i]);
			}
			window.addLast(delta);
			while (window.size() > WINDOW) {
				window.removeFirst();
			}
		}
		last = now;
		int[] sum = new int[now.length];
		for (int[] d : window) {
			for (int i = 0; i < sum.length; i++) {
				sum[i] += d[i];
			}
		}
		return percentile(sum, PERCENTILE);
	}

	/// The `pct` percentile of a histogram, in milliseconds: the lower edge of
	/// the count it falls in, so 200 for the last. -1 for an empty histogram.
	static int percentile(int[] counts, int pct) {
		long total = 0;
		for (int c : counts) {
			total += c;
		}
		if (total == 0) {
			return -1;
		}
		long rank = (total * pct + 99) / 100;
		long seen = 0;
		for (int i = 0; i < counts.length; i++) {
			seen += counts[i];
			if (seen >= rank) {
				return Math.min(CEILING_MILLIS, i * BUCKET_MILLIS);
			}
		}
		return CEILING_MILLIS;
	}
}
