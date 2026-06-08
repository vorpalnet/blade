package org.vorpal.blade.library.fsmar3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.ar.SipApplicationRouterInfo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// The structured routing trace for one call — every state visited, every
/// transition evaluated (and whether it fired), the values extracted at each
/// hop, and the final routing decision.
///
/// This is the **shared trace format**: the engine's opt-in capture (see
/// [Fsmar3Metrics#captureNextCalls]) and the Flow editor's Route Simulator
/// (`FsmarSimulateServlet` in `admin/flow`) both emit this exact JSON shape,
/// so the editor animates a captured production call and a simulated one
/// through the same code path. Change one, change both.
///
/// A *hop* is one state visit: both a normal `getNextApplication` invocation
/// and an undeployed-application bypass iteration produce one hop each.
///
/// Mutators are synchronized: a single call's hops arrive sequentially, but
/// the serializing JMX thread may read while a routing thread writes.
@JsonPropertyOrder({ "callId", "method", "requestUri", "hops", "finalApp", "defaultFallback", "cycleDetected",
		"context" })
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RouteTrace {

	/// One transition evaluation: its id, its `when` condition (null when
	/// unconditional), and whether it fired.
	@JsonPropertyOrder({ "id", "when", "fired" })
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class Eval {
		private final String id;
		private final String when;
		private final boolean fired;

		Eval(String id, String when, boolean fired) {
			this.id = id;
			this.when = when;
			this.fired = fired;
		}

		public String getId() {
			return id;
		}

		public String getWhen() {
			return when;
		}

		public boolean isFired() {
			return fired;
		}
	}

	/// One state visit: the values extracted on entry (pseudo-variables plus
	/// the state's selectors — only the values *added or changed* this hop),
	/// the transitions evaluated in order, and the outcome.
	@JsonPropertyOrder({ "state", "extracted", "evaluated", "matched", "next", "bypassed", "routes", "subscriberURI",
			"region", "routeModifier" })
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class Hop {
		private final String state;
		private Map<String, String> extracted = new LinkedHashMap<>();
		private final List<Eval> evaluated = new ArrayList<>();
		private String matched;
		private String next;
		private boolean bypassed;
		private String[] routes;
		private String subscriberURI;
		private String region;
		private String routeModifier;

		/// Context snapshot taken on hop entry; diffed on hop close to compute
		/// [#extracted]. Null once closed.
		@JsonIgnore
		private Map<String, String> before;

		Hop(String state, Map<String, String> contextNow) {
			this.state = state;
			this.before = new HashMap<>(contextNow);
		}

		public String getState() {
			return state;
		}

		public Map<String, String> getExtracted() {
			return extracted;
		}

		public List<Eval> getEvaluated() {
			return evaluated;
		}

		public String getMatched() {
			return matched;
		}

		public String getNext() {
			return next;
		}

		public boolean isBypassed() {
			return bypassed;
		}

		public String[] getRoutes() {
			return routes;
		}

		public String getSubscriberURI() {
			return subscriberURI;
		}

		public String getRegion() {
			return region;
		}

		public String getRouteModifier() {
			return routeModifier;
		}
	}

	private final String callId;
	private final String method;
	private final String requestUri;
	private final List<Hop> hops = new ArrayList<>();
	private String finalApp;
	private boolean defaultFallback;
	private boolean cycleDetected;
	private Map<String, String> context;

	/// Arrival order for [Fsmar3Metrics#getCapturedTraces] — concurrent map
	/// iteration doesn't preserve it.
	@JsonIgnore
	final long seq;

	@JsonIgnore
	private Hop open;

	RouteTrace(String callId, String method, String requestUri, long seq) {
		this.callId = callId;
		this.method = method;
		this.requestUri = requestUri;
		this.seq = seq;
	}

	/// Closes any open hop (computing its extracted-values diff) and starts a
	/// new one for [state], snapshotting the context as it stands on entry.
	synchronized void beginHop(String state, Map<String, String> contextNow) {
		closeOpenHop(contextNow);
		open = new Hop(state, contextNow);
		hops.add(open);
	}

	/// Records one transition evaluation on the open hop.
	synchronized void evaluated(String id, String when, boolean fired) {
		if (open != null) {
			open.evaluated.add(new Eval(id, when, fired));
		}
	}

	/// Records the matched transition's identity and target on the open hop.
	synchronized void matched(String transitionId, String next) {
		if (open != null) {
			open.matched = transitionId;
			open.next = next;
		}
	}

	/// Marks the open hop as a bypass of an undeployed application.
	synchronized void bypassed() {
		if (open != null) {
			open.bypassed = true;
		}
	}

	/// Records the routing decision the container will act on: final app,
	/// resolved routes, subscriber URI, region, and route modifier.
	synchronized void routed(SipApplicationRouterInfo info) {
		if (info == null) {
			return;
		}
		finalApp = info.getNextApplicationName();
		if (open != null) {
			open.routes = info.getRoutes();
			open.subscriberURI = info.getSubscriberURI();
			if (info.getRoutingRegion() != null) {
				open.region = String.valueOf(info.getRoutingRegion().getType());
			}
			if (info.getRouteModifier() != null) {
				open.routeModifier = info.getRouteModifier().name();
			}
		}
	}

	synchronized void cycle() {
		cycleDetected = true;
	}

	synchronized void defaultFallback(String app) {
		defaultFallback = true;
		if (app != null) {
			finalApp = app;
		}
	}

	/// Ends one `getNextApplication` invocation: closes the open hop and
	/// snapshots the accumulated context. Called once per hop of the call;
	/// the last invocation's snapshot is the one serialized.
	synchronized void endInvocation(Map<String, String> contextNow) {
		closeOpenHop(contextNow);
		if (contextNow != null) {
			context = new LinkedHashMap<>(contextNow);
		}
	}

	private void closeOpenHop(Map<String, String> contextNow) {
		if (open == null) {
			return;
		}
		if (contextNow != null && open.before != null) {
			Map<String, String> diff = new LinkedHashMap<>();
			for (Map.Entry<String, String> e : contextNow.entrySet()) {
				if (!java.util.Objects.equals(e.getValue(), open.before.get(e.getKey()))) {
					diff.put(e.getKey(), e.getValue());
				}
			}
			open.extracted = diff;
		}
		open.before = null;
		open = null;
	}

	public String getCallId() {
		return callId;
	}

	public String getMethod() {
		return method;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public List<Hop> getHops() {
		return hops;
	}

	public String getFinalApp() {
		return finalApp;
	}

	public boolean isDefaultFallback() {
		return defaultFallback;
	}

	public boolean isCycleDetected() {
		return cycleDetected;
	}

	public Map<String, String> getContext() {
		return context;
	}

	/// Serializes under this trace's lock so a routing thread can't mutate the
	/// hop list mid-write (the JMX thread reads while traffic flows).
	synchronized String toJson(com.fasterxml.jackson.databind.ObjectMapper mapper)
			throws com.fasterxml.jackson.core.JsonProcessingException {
		return mapper.writeValueAsString(this);
	}
}
