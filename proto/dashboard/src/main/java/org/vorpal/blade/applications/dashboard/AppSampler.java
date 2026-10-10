package org.vorpal.blade.applications.dashboard;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/// Turns running counters into what happened since the last look: the
/// arithmetic behind [AppSampling], kept apart from JMX so it can be tested.
///
/// **Why deltas, and why here.** An application's counters live in its
/// `Type=Metrics` MBean, a server's in its runtime MBeans, all in memory: they
/// start at zero on every deploy or restart and are gone when the node stops.
/// A total read off them says nothing about when the events were published or
/// dropped, and a restart makes a running series go backwards. A delta per
/// minute is a fact that stays true, so it can be stored and summed over any
/// window afterwards.
///
/// **The first look publishes nothing.** It sets the baseline. Publishing the
/// totals instead would count again everything a previous dashboard instance
/// already recorded, every time this one starts.
///
/// **A restart is seen, not guessed at.** When the uptime goes down, or any
/// counter does, the source restarted between two looks, and its current
/// totals are what it did since: they become the delta. What it did between the
/// last look and the restart is lost; at one look a minute that is at most a
/// minute's worth.
///
/// **Except a total named `...#sum`.** A timing's summed milliseconds may be
/// rebuilt as count times a whole-millisecond mean, which can dip while the
/// true sum grows; read as a restart, that dip would republish every lifetime
/// total as one minute's work. Sums never decide a restart, and a sum that
/// dips counts as zero.
final class AppSampler {

	/// The suffix of a total that may dip without a restart.
	static final String SUM = "#sum";

	/// What one source did since the previous look, by counter name, in the
	/// order the totals were given.
	static final class Delta {
		final String app;
		final String node;
		final String cluster;
		final long seconds;
		final Map<String, Long> counts;
		final boolean restarted;

		Delta(String app, String node, String cluster, long seconds, Map<String, Long> counts, boolean restarted) {
			this.app = app;
			this.node = node;
			this.cluster = cluster;
			this.seconds = seconds;
			this.counts = counts;
			this.restarted = restarted;
		}

		long count(String name) {
			return counts.getOrDefault(name, 0L);
		}

		/// Nothing happened and nothing restarted. Not published: most admin
		/// applications count nothing, and a minute of zeros from each of them
		/// is most of the rows, saying only what the absence of a row says.
		boolean quiet() {
			if (restarted) {
				return false;
			}
			for (long c : counts.values()) {
				if (c != 0) {
					return false;
				}
			}
			return true;
		}
	}

	private static final class Seen {
		final long at;
		final long uptime;
		final Map<String, Long> totals;

		Seen(long at, long uptime, Map<String, Long> totals) {
			this.at = at;
			this.uptime = uptime;
			this.totals = totals;
		}
	}

	private final Map<String, Seen> last = new HashMap<>();

	/// Record one look and return the delta since the previous one, or null on
	/// the first look at this source.
	///
	/// A counter seen for the first time after the baseline (a labelled value
	/// that had not occurred yet) counts from zero.
	///
	/// @param now    when the look was taken, epoch millis
	/// @param uptime the source's own uptime
	/// @param totals the running counters, by name
	Delta observe(String app, String node, String cluster, long now, long uptime, Map<String, Long> totals) {
		String key = app + '\u0000' + node;
		Seen previous = last.put(key, new Seen(now, uptime, new HashMap<>(totals)));
		if (previous == null) {
			return null;
		}
		boolean restarted = uptime < previous.uptime;
		for (Map.Entry<String, Long> e : totals.entrySet()) {
			Long before = previous.totals.get(e.getKey());
			restarted |= before != null && e.getValue() < before && !e.getKey().endsWith(SUM);
		}
		Map<String, Long> counts = new LinkedHashMap<>();
		for (Map.Entry<String, Long> e : totals.entrySet()) {
			long before = restarted ? 0 : previous.totals.getOrDefault(e.getKey(), 0L);
			counts.put(e.getKey(), Math.max(0, e.getValue() - before));
		}
		long seconds = Math.round((restarted ? Math.min(uptime, now - previous.at) : now - previous.at) / 1000.0);
		return new Delta(app, node, cluster, seconds, counts, restarted);
	}
}
