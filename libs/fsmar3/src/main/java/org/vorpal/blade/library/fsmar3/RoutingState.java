package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/// The state FSMAR carries across App Router invocations via the JSR-289
/// `stateInfo` (the `Serializable` round-tripped on every hop of an initial
/// request's routing chain, and replicated across the cluster).
///
/// Holds two things:
/// - [#config] — a pinned snapshot of the configuration, so a mid-flight config
///   reload can't change routing for a call already in progress.
/// - [#context] — the accumulating bag of values the states' selectors have
///   extracted so far. Because it rides the `stateInfo`, values captured in an
///   early state remain available to conditions and `${}` route templates in
///   later states (capture-and-carry down the call-path).
public class RoutingState implements Serializable {
	private static final long serialVersionUID = 1L;

	private final AppRouterConfiguration config;
	private final HashMap<String, String> context;

	public RoutingState(AppRouterConfiguration config) {
		this.config = config;
		this.context = new HashMap<>();
	}

	public AppRouterConfiguration getConfig() {
		return config;
	}

	/// The mutable, accumulating extraction context. Wrapped in a
	/// [org.vorpal.blade.framework.v3.configuration.MemoryContext] each hop.
	public Map<String, String> getContext() {
		return context;
	}
}
