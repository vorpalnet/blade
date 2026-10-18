package org.vorpal.blade.services.options;

/// Whether to refuse new calls, from the engine's latency. It starts refusing
/// when the latency reaches `refuseMillis` and stops once it falls below half
/// of that. The gap keeps a latency that hovers at the line from switching
/// refusing on and off with every reading, which OCCAS's own thresholds cannot
/// do: each has a single value.
///
/// Once refusing, it refuses for at least [#HOLD_MILLIS]. Refused requests
/// cost the engine almost nothing, so its latency falls within a second of
/// refusing; without the hold, the calls would come straight back, the latency
/// with them, and the engine would switch about once a second for as long as
/// the load lasted.
///
/// Not thread-safe; [OverloadMonitor] feeds it from one task at a time.
final class OverloadVerdict {

	/// The least time refusing lasts once it starts.
	static final long HOLD_MILLIS = 10_000;

	private boolean refusing;
	private long since;

	/// Takes one latency reading in milliseconds, taken at `now`, and returns
	/// the verdict after it. After the hold, no reading (-1: an idle engine, or
	/// a histogram that cannot be read) takes calls: refusing must not outlast
	/// the evidence for it. `refuseMillis` of 0 or less turns refusing off at
	/// once, hold or not.
	boolean sample(int latencyMillis, int refuseMillis, long now) {
		if (refuseMillis <= 0) {
			refusing = false;
		} else if (refusing) {
			if (now - since >= HOLD_MILLIS) {
				refusing = latencyMillis >= 0 && latencyMillis * 2 >= refuseMillis;
			}
		} else if (latencyMillis >= refuseMillis) {
			refusing = true;
			since = now;
		}
		return refusing;
	}

	boolean refusing() {
		return refusing;
	}
}
