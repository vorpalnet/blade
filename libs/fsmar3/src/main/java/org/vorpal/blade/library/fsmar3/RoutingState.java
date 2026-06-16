package org.vorpal.blade.library.fsmar3;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/// The state FSMAR carries across App Router invocations via the JSR-289
/// `stateInfo` (the `Serializable` round-tripped on every hop of an initial
/// request's routing chain, and replicated across the cluster).
///
/// Holds the small state that rides the JSR-289 `stateInfo`:
/// - [#context] — the accumulating bag of values the states' selectors have
///   extracted so far. Because it rides the `stateInfo`, values captured in an
///   early state remain available to conditions and `${}` route templates in
///   later states (capture-and-carry down the call-path).
/// - [#currentStateId] — the FSM state the call last routed INTO. Carried here
///   (not re-derived from the SIP session's application name) so the engine can
///   resume at the exact state — which lets two states share one application
///   (same `app`, different ids) and still be told apart on the next hop.
///
/// [#config] is **transient on purpose**: `stateInfo` is serialized into a SIP
/// Route URI on a ROUTE_BACK round-trip (the container BASE64-encodes it into
/// the route it pushes back to itself), so carrying the whole configuration
/// would bloat that header on every route-back call. The App Router re-binds the
/// current configuration on each invocation via [#bindConfig] (see
/// `AppRouter.getNextApplication`). Trade-off: a config reload mid-call now
/// applies to that call's later hops rather than being pinned — acceptable, and
/// what keeps the wire form tiny.
public class RoutingState implements Serializable {
	private static final long serialVersionUID = 2L;

	private transient AppRouterConfiguration config;
	private final HashMap<String, String> context;
	private String currentStateId;

	public RoutingState(AppRouterConfiguration config) {
		this.config = config;
		this.context = new HashMap<>();
	}

	/// The active configuration for this invocation. Null after deserialization
	/// (the field is transient) until the App Router calls [#bindConfig].
	public AppRouterConfiguration getConfig() {
		return config;
	}

	/// (Re)binds the live configuration after construction or deserialization.
	/// The config is never carried in `stateInfo` — see the class note.
	public void bindConfig(AppRouterConfiguration config) {
		this.config = config;
	}

	/// The state id the call last routed into — the state to resume from on the
	/// next App Router invocation. Null before the first routing decision.
	public String getCurrentStateId() {
		return currentStateId;
	}

	public void setCurrentStateId(String currentStateId) {
		this.currentStateId = currentStateId;
	}

	/// The mutable, accumulating extraction context. Wrapped in a
	/// [org.vorpal.blade.framework.v3.configuration.MemoryContext] each hop.
	public Map<String, String> getContext() {
		return context;
	}
}
