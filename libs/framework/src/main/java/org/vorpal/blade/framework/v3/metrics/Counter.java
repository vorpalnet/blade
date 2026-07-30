package org.vorpal.blade.framework.v3.metrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/// A monotonically increasing count, optionally broken out by one label with a
/// **declared, finite** set of values.
///
/// **Why the label space is declared up front.** Unbounded cardinality is the
/// one failure mode that kills in-process metrics: a counter keyed by Call-ID or
/// calling number grows until the node dies. Memory, not CPU, is the real risk
/// here. Declaring the values at registration makes that structurally impossible
/// rather than a rule someone has to remember — anything not declared lands in a
/// single [#OTHER] bucket and is logged once.
///
/// **Hot path.** What actually costs something is not the increment — it is
/// *building the key*. `map.get(callflow + ":" + status)` on every message
/// allocates a string and pressures the collector, and that is what makes
/// counters look expensive. A plain lookup on a string you already hold does
/// not: [#increment(String)] is one hash lookup and no allocation, which is
/// fine at message rates. [#series(String)] goes further and resolves the label
/// to a [Series] handle **once** at initialization, so the call path is a bare
/// [LongAdder] increment with nothing in front of it — use it where a metric is
/// updated several times per message, or in the tightest loops.
///
/// [LongAdder] rather than `AtomicLong` on purpose: its javadoc calls it
/// preferable when many threads update a common sum for statistics collection,
/// with significantly higher throughput under contention. It shards across
/// per-thread cells and only sums on read, which is exactly this access pattern
/// — written constantly, read every few seconds. The same choice `ScenarioStats`
/// already makes in the tester.
public final class Counter {

	/// Bucket for label values that were not declared. Follows the existing
	/// convention in `TesterMetrics.UNSCENARIOED` of a parenthesized sentinel.
	public static final String OTHER = "(other)";

	/// Ceiling on declared label values. A caller asking for more has almost
	/// certainly mistaken an unbounded key (a phone number, a Call-ID) for a
	/// bounded one, which is the mistake this whole design exists to prevent.
	public static final int MAX_LABEL_VALUES = 256;

	private static final Logger logger = Logger.getLogger(Counter.class.getName());

	/// The label key used internally when a counter has no label at all.
	private static final String UNLABELED = "";

	private final String name;
	private final String description;
	private final String labelName;
	private final String[] labels;
	private final Map<String, Integer> indexByLabel;
	private final LongAdder[] adders;
	private final Series[] handles;

	/// Set the first time an undeclared label is seen, so the warning is logged
	/// once rather than at call rate.
	private volatile boolean warnedUndeclared;

	/// An already-resolved counter cell — the hot-path handle.
	///
	/// Hold one of these in a field and call [#increment()] per message. Nothing
	/// here allocates or looks anything up.
	public static final class Series {

		private final LongAdder adder;

		private Series(LongAdder adder) {
			this.adder = adder;
		}

		/// Add one.
		public void increment() {
			adder.increment();
		}

		/// Add an arbitrary non-negative amount.
		public void add(long delta) {
			adder.add(delta);
		}

		/// This cell's current value.
		public long value() {
			return adder.sum();
		}
	}

	/// An unlabeled counter.
	Counter(String name, String description) {
		this(name, description, null, null);
	}

	/// A counter broken out by one label.
	///
	/// @param name          metric name, unique within the app
	/// @param description   what it counts, shown in the console
	/// @param labelName     the dimension name, e.g. `outcome` or `callflow`
	/// @param labelValues   the complete, finite set of values; an undeclared
	///                      value is counted under [#OTHER]
	Counter(String name, String description, String labelName, List<String> labelValues) {
		this.name = name;
		this.description = description;
		this.labelName = labelName;

		List<String> declared = new ArrayList<>();
		if (labelName == null) {
			declared.add(UNLABELED);
		} else {
			if (labelValues == null || labelValues.isEmpty()) {
				throw new IllegalArgumentException(
						"counter '" + name + "' declares label '" + labelName + "' with no values; "
								+ "the value set must be finite and known at registration");
			}
			if (labelValues.size() > MAX_LABEL_VALUES) {
				throw new IllegalArgumentException("counter '" + name + "' declares " + labelValues.size()
						+ " label values, over the limit of " + MAX_LABEL_VALUES
						+ ". A key space this large is usually an unbounded one (a number, an id) in disguise.");
			}
			for (String value : labelValues) {
				if (value != null && !value.isEmpty() && !declared.contains(value)) {
					declared.add(value);
				}
			}
			declared.add(OTHER);
		}

		this.labels = declared.toArray(new String[0]);
		this.adders = new LongAdder[labels.length];
		this.handles = new Series[labels.length];
		this.indexByLabel = new HashMap<>();
		for (int i = 0; i < labels.length; i++) {
			adders[i] = new LongAdder();
			handles[i] = new Series(adders[i]);
			indexByLabel.put(labels[i], Integer.valueOf(i));
		}
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	/// The label dimension, or null when this counter has none.
	public String getLabelName() {
		return labelName;
	}

	/// The declared label values, including [#OTHER]. Empty for an unlabeled
	/// counter.
	public List<String> getLabelValues() {
		List<String> values = new ArrayList<>();
		if (labelName != null) {
			for (String label : labels) {
				values.add(label);
			}
		}
		return values;
	}

	/// Resolve a label to its cell **once**, then hold the result and call
	/// [Series#increment()] on the hot path.
	///
	/// @param label a declared label value, or null/unknown for [#OTHER]
	public Series series(String label) {
		return handles[indexOf(label)];
	}

	/// The cell of an unlabeled counter.
	public Series series() {
		return handles[0];
	}

	/// Convenience increment that resolves the label each call. Fine for
	/// startup, shutdown and administrative paths; prefer [#series(String)] for
	/// anything per-message.
	public void increment(String label) {
		adders[indexOf(label)].increment();
	}

	/// Convenience increment for an unlabeled counter.
	public void increment() {
		adders[0].increment();
	}

	private int indexOf(String label) {
		if (labelName == null) {
			return 0;
		}
		Integer found = indexByLabel.get(label == null ? OTHER : label);
		if (found != null) {
			return found.intValue();
		}
		if (!warnedUndeclared) {
			warnedUndeclared = true;
			logger.log(Level.WARNING, "metric ''{0}'' saw undeclared {1} value ''{2}''; counting it under {3}. "
					+ "Declare it at registration, or the console will not break it out.",
					new Object[] { name, labelName, label, OTHER });
		}
		return indexByLabel.get(OTHER).intValue();
	}

	/// This counter's value for one label.
	public long value(String label) {
		return adders[indexOf(label)].sum();
	}

	/// The sum across every label.
	public long total() {
		long total = 0;
		for (LongAdder adder : adders) {
			total += adder.sum();
		}
		return total;
	}

	/// Drop every cell to zero.
	public void reset() {
		for (LongAdder adder : adders) {
			adder.reset();
		}
	}

	/// A snapshot for the report. Values are read one cell at a time and are not
	/// a consistent instant across labels — which is correct for counters, where
	/// a torn read costs at most one message and blocking the call path to avoid
	/// it would not.
	public CounterReport report() {
		CounterReport report = new CounterReport();
		report.setName(name);
		report.setDescription(description);
		report.setLabel(labelName);
		report.setTotal(total());
		if (labelName != null) {
			Map<String, Long> values = new LinkedHashMap<>();
			for (int i = 0; i < labels.length; i++) {
				values.put(labels[i], Long.valueOf(adders[i].sum()));
			}
			report.setValues(values);
		}
		return report;
	}
}
