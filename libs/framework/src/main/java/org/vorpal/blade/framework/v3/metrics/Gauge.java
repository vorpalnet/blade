package org.vorpal.blade.framework.v3.metrics;

import java.util.function.LongSupplier;

/// A value read on demand rather than accumulated — active sessions, queue
/// depth, pool size.
///
/// Costs nothing on the call path by construction: the supplier runs only when
/// the console asks, every few seconds, not when the thing it measures changes.
/// That makes a gauge the right choice for anything already tracked somewhere
/// else, and the wrong choice for anything expensive to compute — the supplier
/// runs on the JMX read thread, so it must not block or scan.
public final class Gauge {

	private final String name;
	private final String description;
	private final LongSupplier supplier;

	Gauge(String name, String description, LongSupplier supplier) {
		if (supplier == null) {
			throw new IllegalArgumentException("gauge '" + name + "' needs a supplier");
		}
		this.name = name;
		this.description = description;
		this.supplier = supplier;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	/// Read the current value. A supplier that throws yields zero rather than
	/// failing the whole report — one broken gauge must not blind the console to
	/// every other metric on the node.
	public long value() {
		try {
			return supplier.getAsLong();
		} catch (RuntimeException e) {
			return 0;
		}
	}

	public GaugeReport report() {
		GaugeReport report = new GaugeReport();
		report.setName(name);
		report.setDescription(description);
		report.setValue(value());
		return report;
	}
}
