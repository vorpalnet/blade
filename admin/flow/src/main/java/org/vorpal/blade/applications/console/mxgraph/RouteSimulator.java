package org.vorpal.blade.applications.console.mxgraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.vorpal.blade.framework.v3.configuration.Context;
import org.vorpal.blade.framework.v3.configuration.MemoryContext;
import org.vorpal.blade.framework.v3.configuration.expressions.Expression;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Runs a synthetic SIP request through an FSMAR 3 configuration and returns
/// the full routing trace — every state visited, every transition evaluated
/// (with its `when` and whether it fired), the values each hop extracted, and
/// the final decision.
///
/// **This mirrors `AppRouter.getNextApplication` in `libs/fsmar3` — that loop
/// is the source of truth.** The hard semantics (selector extraction,
/// `Expression` evaluation, `${}` route resolution, translation tables) run
/// through the very same framework v3 classes the engine bundles; only the
/// ~80-line state-machine walk is mirrored here, because the engine ships as
/// an approuter fat JAR this WAR can't depend on. If the engine loop changes,
/// change this too.
///
/// The output is the **shared trace format** also produced by the engine's
/// opt-in capture (`RouteTrace` in `libs/fsmar3`) — the Flow editor animates
/// simulated and captured (replayed) calls through one code path. The only
/// additions here are `problems[]` (simulator-side notes: a selector that
/// wouldn't deserialize, the hop cap) and the absence of `callId`.
///
/// Differences from the engine, by necessity:
/// - "previous application" is always `"null"` — a simulation is of an
///   initial request entering the machine
/// - "deployed" is inverted: every application named in the config counts as
///   deployed except those listed in `undeployed` (so bypass/cycle/fallback
///   behavior can be explored before anything is actually deployed)
/// - the subscriber URI is extracted from the named header's value textually
///   (no container `getAddressHeader`)
/// - the engine's two pre-FSM shortcuts (targeted sessions, and a user-less
///   Request-URI whose host names a deployed application) are not simulated —
///   the simulation always walks the state machine
/// - the message never changes between hops (real applications may rewrite
///   headers mid-path), so a later state's selectors re-read the original
///   values — relevant when simulating re-capture states like the sample's
///   `callerNow`
public final class RouteSimulator {

	/// Safety net mirroring nothing in the engine (the engine's visited-set
	/// makes unbounded walks impossible; here a malformed hand-built request
	/// could still loop wide before cycling).
	static final int MAX_HOPS = 32;

	private RouteSimulator() {
	}

	/// Simulates one request. `simRequest` is the editor's POST body:
	/// `{ config, request: {method, requestUri, headers}, pseudo, undeployed }`.
	public static ObjectNode simulate(JsonNode simRequest, ObjectMapper mapper) {
		JsonNode config = simRequest.path("config");
		JsonNode req = simRequest.path("request");
		String method = req.path("method").asText("INVITE");
		String requestUri = req.path("requestUri").asText("");

		List<String> problems = new ArrayList<>();

		// The selector payload — exactly the Map contract the framework
		// selectors (and the fsmar3 smoke tests) read: header name → value.
		Map<String, Object> payload = new LinkedHashMap<>();
		Iterator<Map.Entry<String, JsonNode>> headers = req.path("headers").fields();
		while (headers.hasNext()) {
			Map.Entry<String, JsonNode> h = headers.next();
			payload.put(h.getKey(), h.getValue().asText());
		}
		if (!requestUri.isEmpty()) {
			payload.putIfAbsent("Request-URI", requestUri);
		}

		Set<String> undeployed = new HashSet<>();
		for (JsonNode u : simRequest.path("undeployed")) {
			undeployed.add(u.asText());
		}

		// Routing context — same map-backed Context the engine carries in
		// stateInfo. The first hop's diff baseline is captured BEFORE the
		// pseudo-variables publish, so they show in hop 1's extracted values
		// (matching the engine's capture).
		Map<String, String> contextMap = new LinkedHashMap<>();
		Context ctx = new MemoryContext(contextMap);
		Map<String, String> beforeFirstHop = new HashMap<>(contextMap);
		publishPseudoVariables(contextMap, method, requestUri,
				(String) payload.get("Call-ID"), simRequest.path("pseudo"));

		ObjectNode out = mapper.createObjectNode();
		out.put("method", method);
		out.put("requestUri", requestUri);
		ArrayNode hops = out.putArray("hops");

		String finalApp = null;
		boolean defaultFallback = false;
		boolean cycleDetected = false;

		// ===== Mirror of AppRouter.getNextApplication's state-machine walk,
		// chained across invocations =====
		// The engine is called once per application the call traverses; the
		// context accumulates in stateInfo between calls. The simulation
		// chains those invocations: whenever a transition routes to a
		// "deployed" application, the walk continues from that application's
		// state — so one simulation traces the WHOLE call path
		// (ingress → screening → b2bua → …), which is what the editor
		// animates. The visited-set cycle guard is per-invocation, exactly
		// like the engine's; MAX_HOPS catches cross-invocation config loops
		// (which would compose applications endlessly in production too).
		String previous = "null";
		Set<String> visited = new HashSet<>();
		visited.add(previous);

		String defaultApplication = config.path("defaultApplication").asText("");
		JsonNode states = config.path("states");
		Map<String, List<Selector>> selectorCache = new HashMap<>();

		boolean done = false;
		while (!done) {
			if (hops.size() >= MAX_HOPS) {
				problems.add("Stopped after " + MAX_HOPS
						+ " hops — the configuration loops between applications");
				break;
			}

			ObjectNode hop = hops.addObject();
			hop.put("state", previous);
			Map<String, String> before = (beforeFirstHop != null) ? beforeFirstHop : new HashMap<>(contextMap);
			beforeFirstHop = null;

			// Keep ${previousApp} current as the walk advances (engine does
			// the same inside its loop).
			contextMap.put("previousApp", previous);

			// On entry to a state, run its selectors (best-effort, like
			// State.extract — a failing selector must not abort routing).
			JsonNode state = states.path(previous);
			if (state.isObject()) {
				for (Selector s : selectors(previous, state, selectorCache, mapper, problems)) {
					try {
						s.extract(ctx, payload);
					} catch (Exception e) {
						// best-effort, mirrors State.extract
					}
				}
			}
			hop.set("extracted", diff(before, contextMap, mapper));

			JsonNode trigger = state.path("triggers").path(method);
			JsonNode transitions = trigger.path("transitions");

			// Evaluate transitions in order; first match wins.
			ObjectNode matched = null;
			ArrayNode evaluated = hop.putArray("evaluated");
			for (JsonNode t : transitions) {
				String when = t.path("when").asText("");
				boolean fired = matches(when, ctx);
				ObjectNode eval = evaluated.addObject();
				if (t.hasNonNull("id")) {
					eval.put("id", t.path("id").asText());
				}
				if (!when.isEmpty()) {
					eval.put("when", when);
				}
				eval.put("fired", fired);
				if (fired) {
					matched = (ObjectNode) t;
					break;
				}
			}
			if (matched == null && trigger.isObject() && transitions.size() == 0) {
				// Trigger defined with no transitions — implicit match, no action.
				matched = mapper.createObjectNode();
			}

			hop.put("bypassed", false);
			String routedTo = null;

			if (matched == null) {
				// No transition: this invocation routes nothing.
				done = true;
			} else {
				if (matched.hasNonNull("id")) {
					hop.put("matched", matched.path("id").asText());
				}
				String next = matched.path("next").asText(null);
				if (next != null) {
					hop.put("next", next);
				}

				if (next == null) {
					// Matched with no target application — nothing to route to.
					done = true;
				} else if (undeployed.contains(next)) {
					// Bypass the undeployed application and continue from its
					// state (same invocation). Stop on a revisit (config loop).
					hop.put("bypassed", true);
					if (!visited.add(next)) {
						cycleDetected = true;
						done = true;
					} else {
						previous = next;
					}
				} else {
					// "Deployed": route here. Resolve ${} routes against the
					// context, exactly as Transition.createRouterInfo does.
					JsonNode routes = matched.path("routes");
					if (routes.isArray() && routes.size() > 0) {
						ArrayNode resolved = hop.putArray("routes");
						for (JsonNode r : routes) {
							resolved.add(ctx.resolve(r.asText()));
						}
						hop.put("routeModifier", matched.path("routeModifier").asText("ROUTE"));
					} else {
						hop.put("routeModifier", "NO_ROUTE");
					}
					String subscriber = matched.path("subscriber").asText("");
					if (!subscriber.isEmpty()) {
						String uri = extractUri((String) payload.get(subscriber));
						if (uri != null) {
							hop.put("subscriberURI", uri);
						}
					}
					hop.put("region", matched.path("region").asText("NEUTRAL"));
					finalApp = next;
					routedTo = next;
				}
			}

			// Default application fallback — engine condition verbatim: fires
			// only while the walk never advanced past the initial state.
			if (done && previous.equals("null") && finalApp == null) {
				defaultFallback = true;
				if (defaultApplication.isEmpty()) {
					problems.add("No defaultApplication configured — the engine would route to null");
				} else if (undeployed.contains(defaultApplication)) {
					problems.add("defaultApplication '" + defaultApplication
							+ "' is marked undeployed — the engine would route to null");
				} else {
					finalApp = defaultApplication;
					routedTo = defaultApplication;
					done = false;
				}
			}

			if (routedTo != null) {
				// The container invokes the routed application and the call
				// continues from its state — the next getNextApplication
				// invocation, with a fresh per-invocation visited set.
				previous = routedTo;
				visited.clear();
				visited.add(previous);
			}
		}
		// ===== end mirror =====

		if (finalApp != null) {
			out.put("finalApp", finalApp);
		}
		out.put("defaultFallback", defaultFallback);
		out.put("cycleDetected", cycleDetected);
		ObjectNode finalContext = out.putObject("context");
		for (Map.Entry<String, String> e : contextMap.entrySet()) {
			finalContext.put(e.getKey(), e.getValue());
		}
		if (!problems.isEmpty()) {
			ArrayNode p = out.putArray("problems");
			for (String s : problems) {
				p.add(s);
			}
		}
		return out;
	}

	/// Deserializes a state's selectors into the real framework [Selector]
	/// classes (polymorphic by `type`). One bad selector is reported and
	/// skipped, not fatal — the editor's validate pass is the gate.
	private static List<Selector> selectors(String stateName, JsonNode state,
			Map<String, List<Selector>> cache, ObjectMapper mapper, List<String> problems) {
		List<Selector> cached = cache.get(stateName);
		if (cached != null) {
			return cached;
		}
		List<Selector> list = new ArrayList<>();
		int i = 0;
		for (JsonNode sel : state.path("selectors")) {
			try {
				list.add(mapper.treeToValue(sel, Selector.class));
			} catch (Exception e) {
				problems.add("states['" + stateName + "'].selectors[" + i
						+ "] could not be loaded and was skipped: " + e.getMessage());
			}
			i++;
		}
		cache.put(stateName, list);
		return list;
	}

	/// Mirrors Transition.matches: empty/absent matches unconditionally,
	/// parse/eval errors resolve to false (a malformed condition can't abort
	/// the routing decision).
	private static boolean matches(String when, Context ctx) {
		if (when == null || when.isEmpty()) {
			return true;
		}
		try {
			return new Expression(when).evaluate(ctx);
		} catch (Exception e) {
			return false;
		}
	}

	/// Mirrors `AppRouter.publishPseudoVariables`, then applies the editor's
	/// explicit overrides (so "simulate Sunday 3 AM" or a fixed `${hash100}`
	/// bucket is one form field, not a wait until Sunday).
	private static void publishPseudoVariables(Map<String, String> ctx, String method,
			String requestUri, String callId, JsonNode overrides) {
		ctx.put("method", method != null ? method : "");
		ctx.put("requestUri", requestUri != null ? requestUri : "");
		ctx.put("directive", "NEW");
		ctx.put("region", "");
		ctx.put("previousApp", "null");

		java.time.LocalDateTime now = java.time.LocalDateTime.now();
		ctx.put("hour", String.valueOf(now.getHour()));
		ctx.put("dayOfWeek", now.getDayOfWeek().name());

		if (callId != null) {
			ctx.put("hash100", String.valueOf(Math.floorMod(callId.hashCode(), 100)));
		}

		if (overrides != null && overrides.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> it = overrides.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> e = it.next();
				ctx.put(e.getKey(), e.getValue().asText());
			}
		}
	}

	/// The values added or changed by this hop (pseudo-variables + selectors).
	private static ObjectNode diff(Map<String, String> before, Map<String, String> after,
			ObjectMapper mapper) {
		ObjectNode d = mapper.createObjectNode();
		for (Map.Entry<String, String> e : after.entrySet()) {
			if (!Objects.equals(e.getValue(), before.get(e.getKey()))) {
				d.put(e.getKey(), e.getValue());
			}
		}
		return d;
	}

	/// Textual stand-in for the container's `getAddressHeader().getURI()`:
	/// the URI between angle brackets, else the value up to any parameters.
	static String extractUri(String headerValue) {
		if (headerValue == null || headerValue.isEmpty()) {
			return null;
		}
		int lt = headerValue.indexOf('<');
		int gt = headerValue.indexOf('>');
		if (lt >= 0 && gt > lt) {
			return headerValue.substring(lt + 1, gt);
		}
		int sc = headerValue.indexOf(';');
		return (sc >= 0 ? headerValue.substring(0, sc) : headerValue).trim();
	}
}
