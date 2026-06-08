package org.vorpal.blade.framework.v3.tester;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletContext;

/// Per-node, per-WAR metrics registry: scenario name → [ScenarioStats].
/// Held on the ServletContext (one instance per deployment per node — never
/// a singleton; every node reports independently and the admin tier
/// aggregates over federated JMX).
public class TesterMetrics {

	public static final String ATTR = "testerMetrics";

	/// Bucket for calls that ran without a named scenario.
	public static final String UNSCENARIOED = "(none)";

	private final ConcurrentHashMap<String, ScenarioStats> stats = new ConcurrentHashMap<>();

	/// Returns this deployment's metrics, creating them on first use.
	public static TesterMetrics from(ServletContext servletContext) {
		TesterMetrics metrics = (TesterMetrics) servletContext.getAttribute(ATTR);
		if (metrics == null) {
			metrics = new TesterMetrics();
			servletContext.setAttribute(ATTR, metrics);
		}
		return metrics;
	}

	/// Counters for the named scenario; null maps to [#UNSCENARIOED].
	public ScenarioStats scenario(String name) {
		return stats.computeIfAbsent(name != null ? name : UNSCENARIOED, k -> new ScenarioStats());
	}

	/// Snapshot of every scenario seen since startup (or the last reset).
	public List<ScenarioReport> report() {
		List<ScenarioReport> out = new ArrayList<>();
		stats.forEach((name, s) -> out.add(s.report(name)));
		out.sort((a, b) -> a.getScenario().compareToIgnoreCase(b.getScenario()));
		return out;
	}

	/// Drops all counters — the next call starts fresh.
	public void reset() {
		stats.clear();
	}
}
