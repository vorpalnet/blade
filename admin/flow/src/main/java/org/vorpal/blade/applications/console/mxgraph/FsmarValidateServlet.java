package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.framework.v3.configuration.expressions.Expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Validates FSMAR 3 JSON semantically — the checks the schema-driven form
/// can't do. POST a `json` parameter; returns
/// `{ "errors": [..], "warnings": [..], "infos": [..] }`.
///
/// - **errors** — the config will not route as written: a `when` expression
///   that doesn't parse (fsmar3 evaluates a malformed condition to false at
///   runtime — i.e. the transition silently never fires), an unknown
///   region/routeModifier value, a regex selector whose pattern doesn't
///   compile.
/// - **warnings** — probably a mistake: unknown fields (preserved by the
///   editor's round-trip passthrough, but a likely typo of a real field),
///   a selector without an id (its value has no variable name), states
///   defined but unreachable.
/// - **infos** — worth knowing: a `next` naming an application with no state
///   entry (legal — a terminal app), a terminal transition with no routes
///   (legal — the downstream exit), no defaultApplication.
///
/// Uses the same Expression parser the router itself compiles `when` with
/// (framework v3), so "parses here" means "parses on the engine".
@WebServlet("/fsmarValidate")
public class FsmarValidateServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private final ObjectMapper mapper = new ObjectMapper();

	// Read from the framework model (see FsmarMeta), not transcribed here — a
	// new selector subclass or routing region must not need an edit in this
	// file to stop being reported as invalid.
	private static final Set<String> REGIONS = FsmarMeta.REGION_SET;
	private static final Set<String> ROUTE_MODIFIERS = FsmarMeta.ROUTE_MODIFIER_SET;
	private static final Set<String> SELECTOR_TYPES = FsmarMeta.SELECTOR_TYPE_SET;
	/// SIP methods that can start a dialog (or are dialog-less). The container
	/// invokes an application router for **initial requests only** — in-dialog
	/// requests follow the path established at setup — so a trigger keyed by
	/// BYE, CANCEL, ACK, INFO, PRACK or UPDATE can never fire.
	static final Set<String> INITIAL_REQUEST_METHODS = FsmarMeta.METHOD_SET;

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String json = request.getParameter("json");
		if (json == null || json.isEmpty()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing json parameter");
			return;
		}

		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> infos = new ArrayList<>();

		try {
			JsonNode root = mapper.readTree(json);
			validate(root, errors, warnings, infos);
		} catch (IOException e) {
			errors.add("Not valid JSON: " + e.getMessage());
		}

		ObjectNode out = mapper.createObjectNode();
		fill(out.putArray("errors"), errors);
		fill(out.putArray("warnings"), warnings);
		fill(out.putArray("infos"), infos);

		response.setContentType("application/json; charset=UTF-8");
		PrintWriter w = response.getWriter();
		w.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
		w.flush();
	}

	private static void fill(ArrayNode arr, List<String> items) {
		for (String s : items) arr.add(s);
	}

	void validate(JsonNode root, List<String> errors, List<String> warnings, List<String> infos) {
		if (!root.isObject()) {
			errors.add("Top level must be a JSON object");
			return;
		}

		if (root.path("defaultApplication").asText("").isEmpty()) {
			infos.add("No defaultApplication — initial requests matching no transition will not route");
		}

		// Root-level: logging/session are real base-Configuration fields;
		// version is framework-managed. "about" is tolerated as legacy — the
		// field was removed framework-wide, but old on-disk configs may still
		// carry an "about" block, and that shouldn't trip a typo warning.
		// Anything else is probably a typo.
		warnUnknown(root, FsmarImportServlet.ROOT_KNOWN, "root", warnings,
				"version", "logging", "session", "about");

		// Ingress entry states (diagram.ingresses): each names a state and a
		// source-match. Warn on a missing match (a named ingress with no match
		// catches nothing — its dispatch transition can't be generated) and on
		// an ingress whose state doesn't exist.
		JsonNode ingresses = root.path("diagram").path("ingresses");
		if (ingresses.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> it = ingresses.fields();
			while (it.hasNext()) {
				Map.Entry<String, JsonNode> e = it.next();
				String name = e.getKey();
				if (e.getValue().path("match").asText("").isEmpty()) {
					warnings.add("ingress '" + name + "' has no source match — it can't be"
							+ " reached (only the default ingress catches unmatched traffic)");
				}
				if (!root.path("states").path(name).isObject()) {
					warnings.add("ingress '" + name + "' has no matching state entry");
				}
			}
		}

		JsonNode states = root.path("states");
		if (!states.isObject() || states.size() == 0) {
			warnings.add("No states defined — the router will only ever use defaultApplication");
			return;
		}

		// Pass 1: which states are targeted by some transition (for
		// reachability) and what every transition's next points at.
		Set<String> targeted = FsmarImportServlet.setOf();
		Iterator<Map.Entry<String, JsonNode>> it = states.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> stateEntry = it.next();
			JsonNode triggers = stateEntry.getValue().path("triggers");
			if (!triggers.isObject()) continue;
			Iterator<Map.Entry<String, JsonNode>> trigIt = triggers.fields();
			while (trigIt.hasNext()) {
				for (JsonNode tx : trigIt.next().getValue().path("transitions")) {
					String next = tx.path("next").asText("");
					if (!next.isEmpty()) targeted.add(next);
				}
			}
		}

		// A selector id is the name of the context variable it writes, so a
		// repeat means the later selector overwrites the earlier one's value —
		// last writer wins, config-wide, since the context spans states.
		// Usually a mistake, no longer fatal: v3's Selector deliberately does
		// not carry @JsonIdentityInfo (it did until 2026-08-01, which made a
		// duplicate fail the entire config to load). Tracked across every
		// state, id -> where it was first seen.
		Map<String, String> selectorIdOwner = new java.util.LinkedHashMap<>();

		// Pass 2: per-state checks
		it = states.fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> stateEntry = it.next();
			String stateName = stateEntry.getKey();
			JsonNode state = stateEntry.getValue();
			String at = "states['" + stateName + "']";

			if (!state.isObject()) {
				errors.add(at + " must be a JSON object");
				continue;
			}

			// Reachability: the "null" state is entered by every initial
			// request; others must be some transition's next.
			if (!"null".equals(stateName) && !targeted.contains(stateName)) {
				warnings.add(at + " is unreachable — no transition routes to it"
						+ " (it only matters if '" + stateName + "' can be a previous app some other way)");
			}

			// `app` on the entry state selects nothing. The walk only ever
			// resolves a *target* state's app (AppRouter:379) — never the
			// state it is currently standing in — and "null" means "no
			// previous application", not an application. Setting it only
			// rewrites ${previousApp} on the first hop, where it then
			// contradicts ${previousState}.
			if ("null".equals(stateName) && !state.path("app").asText("").isEmpty()) {
				warnings.add(at + ".app has no effect — the entry state is not an application."
						+ " It only changes ${previousApp} on the first hop (where ${previousState}"
						+ " still reports 'null'), and redirects any transition with next: \"null\"");
			}

			warnUnknown(state, FsmarImportServlet.STATE_KNOWN, at, warnings);

			// Selectors
			JsonNode selectors = state.path("selectors");
			if (!selectors.isMissingNode() && !selectors.isArray()) {
				errors.add(at + ".selectors must be an array");
			} else if (selectors.isArray()) {
				int i = 0;
				for (JsonNode sel : selectors) {
					String selAt = at + ".selectors[" + i + "]";
					validateSelector(sel, selAt, errors, warnings);
					String selId = sel.path("id").asText("");
					if (!selId.isEmpty()) {
						String owner = selectorIdOwner.putIfAbsent(selId, selAt);
						if (owner != null) {
							warnings.add(selAt + " reuses selector id '" + selId + "' (already used by "
									+ owner + ") — the id is the context variable name and the context"
									+ " spans states, so whichever runs later overwrites the earlier"
									+ " value. Use distinct ids unless that is what you want");
						}
					}
					i++;
				}
			}

			// Triggers / transitions
			JsonNode triggers = state.path("triggers");
			if (!triggers.isMissingNode() && !triggers.isObject()) {
				errors.add(at + ".triggers must be an object keyed by SIP method");
				continue;
			}
			Iterator<Map.Entry<String, JsonNode>> trigIt = triggers.fields();
			while (trigIt.hasNext()) {
				Map.Entry<String, JsonNode> trigEntry = trigIt.next();
				String method = trigEntry.getKey();
				String trigAt = at + ".triggers['" + method + "']";
				JsonNode trigger = trigEntry.getValue();

				// Reachable at all? The App Router only ever sees initial
				// requests, so an in-dialog method here is dead config — it
				// serializes fine and simply never matches.
				if (!INITIAL_REQUEST_METHODS.contains(method)) {
					errors.add(trigAt + " can never fire — the application router is only invoked"
							+ " for initial requests, and '" + method + "' is not one of: "
							+ INITIAL_REQUEST_METHODS);
				}

				warnUnknown(trigger, FsmarImportServlet.TRIGGER_KNOWN, trigAt, warnings);

				JsonNode txList = trigger.path("transitions");
				if (!txList.isArray()) {
					warnings.add(trigAt + " has no transitions array");
					continue;
				}
				int i = 0;
				for (JsonNode tx : txList) {
					validateTransition(tx, states, trigAt + ".transitions[" + i + "]",
							errors, warnings, infos);
					i++;
				}
				validateShadowing(txList, trigAt, warnings);
				validateConstantConditions(txList, stateName, state, trigAt, warnings);
			}
		}
	}

	/// Flags a `when` that tests `${previousState}` or `${previousApp}`.
	///
	/// Both are constants inside a condition. `AppRouter` sets them from the
	/// same state id it just used to look the state up (`previousState` = the
	/// enclosing state's key; `previousApp` = that state's `app`, defaulting to
	/// the key) immediately before evaluating that state's transitions. So a
	/// comparison against either is always-true or always-false for every call
	/// — it reads like a discriminator and behaves like an unconditional
	/// transition, silently making everything below it unreachable.
	///
	/// [#validateShadowing] cannot see this: the `when` is neither empty nor a
	/// duplicate. The variables are genuinely useful in **route** templates,
	/// where they name the application that just ran — this only applies to
	/// `when`.
	private static void validateConstantConditions(JsonNode txList, String stateName,
			JsonNode state, String trigAt, List<String> warnings) {
		String app = state.path("app").asText("");
		String previousApp = app.isEmpty() ? stateName : app;
		int i = 0;
		for (JsonNode tx : txList) {
			String when = tx.path("when").asText("");
			if (when.contains("${previousState}")) {
				warnings.add(trigAt + ".transitions[" + i + "].when tests ${previousState},"
						+ " which is always '" + stateName + "' here — the condition is a constant,"
						+ " not a test. Use it in a route template instead");
			}
			if (when.contains("${previousApp}")) {
				warnings.add(trigAt + ".transitions[" + i + "].when tests ${previousApp},"
						+ " which is always '" + previousApp + "' here — the condition is a constant,"
						+ " not a test. Use it in a route template instead");
			}
			i++;
		}
	}

	/// First-match-wins shadowing checks over one trigger's ordered
	/// transitions list — the evaluation-order foot-gun a canvas can't show:
	/// - an unconditional transition (empty `when`) anywhere but last makes
	///   every later transition unreachable
	/// - two transitions with the identical `when` — the later never fires
	private static void validateShadowing(JsonNode txList, String trigAt, List<String> warnings) {
		int unconditionalAt = -1;
		Map<String, Integer> firstByWhen = new java.util.HashMap<>();
		int i = 0;
		for (JsonNode tx : txList) {
			String when = tx.path("when").asText("");
			if (unconditionalAt >= 0) {
				warnings.add(trigAt + ".transitions[" + i + "] is unreachable — transitions["
						+ unconditionalAt + "] has no 'when' and always matches first");
			} else if (when.isEmpty()) {
				unconditionalAt = i;
			} else {
				Integer first = firstByWhen.putIfAbsent(when, i);
				if (first != null) {
					warnings.add(trigAt + ".transitions[" + i + "] is shadowed — transitions["
							+ first + "] has the identical 'when' and always matches first");
				}
			}
			i++;
		}
	}

	private void validateSelector(JsonNode sel, String at, List<String> errors, List<String> warnings) {
		if (!sel.isObject()) {
			errors.add(at + " must be a JSON object");
			return;
		}
		String type = sel.path("type").asText("attribute");
		if (!SELECTOR_TYPES.contains(type)) {
			errors.add(at + " has unknown type '" + type + "' — one of: " + SELECTOR_TYPES);
		}
		if (sel.path("id").asText("").isEmpty()) {
			warnings.add(at + " has no id — the extracted value has no variable name");
		}
		if ("regex".equals(type)) {
			String pattern = sel.path("pattern").asText("");
			if (pattern.isEmpty()) {
				warnings.add(at + " is a regex selector with no pattern");
			} else {
				try {
					Pattern.compile(pattern);
				} catch (PatternSyntaxException e) {
					errors.add(at + ".pattern does not compile: " + e.getDescription());
				}
			}
		} else {
			if (!sel.path("pattern").asText("").isEmpty()) {
				warnings.add(at + " has a pattern but type '" + type + "' ignores it");
			}
		}
		if ("table".equals(type) && sel.path("table").isMissingNode()) {
			warnings.add(at + " is a table selector with no table");
		}
		warnUnknown(sel, FsmarImportServlet.SELECTOR_KNOWN, at, warnings,
				// legitimate type-specific fields, not typos:
				"table", "namespaces");
	}

	private void validateTransition(JsonNode tx, JsonNode states, String at,
			List<String> errors, List<String> warnings, List<String> infos) {
		if (!tx.isObject()) {
			errors.add(at + " must be a JSON object");
			return;
		}

		String next = tx.path("next").asText("");
		boolean hasRoutes = tx.path("routes").isArray() && tx.path("routes").size() > 0;
		if (next.isEmpty()) {
			// A terminal transition with routes is an egress (the call leaves
			// OCCAS via them); with NO routes it is the downstream exit —
			// application chaining stops and OCCAS routes the request on its
			// Request-URI (AppRouter's no-target break). Legal, worth noting.
			// A matched stop counts as a match, so the defaultApplication
			// fallback (for "no matches whatsoever") never overrides it — even
			// on the entry state.
			if (!hasRoutes) {
				infos.add(at + " has no 'next' and no 'routes' — terminal: application"
						+ " chaining stops and the request routes downstream on the Request-URI");
			}
		} else if (!"null".equals(next) && !states.has(next)) {
			infos.add(at + " routes to '" + next + "' which has no state entry — "
					+ "fine for a terminal application, a typo otherwise");
		}

		// next + ROUTE_BACK + routes is not an ordinary hop. AppRouter checks
		// this shape (:360-371) BEFORE it resolves the target application, and
		// returns a null-app decision: the call leaves OCCAS via these routes
		// and `next` is where it RESUMES when the container routes it back.
		// The target's application does not run now. On the canvas this looks
		// like any other arrow, and the editor's own way of drawing it is an
		// egress node with a return state — so say so.
		if (!next.isEmpty() && hasRoutes
				&& "ROUTE_BACK".equals(tx.path("routeModifier").asText(""))) {
			warnings.add(at + " is a route-back, not a hop to '" + next + "': the call leaves"
					+ " OCCAS via these routes first and only resumes at '" + next + "' when it"
					+ " comes back, so that application does not run on this hop."
					+ " The editor draws this as an egress node with a return state");
		}

		String when = tx.path("when").asText("");
		if (!when.isEmpty()) {
			try {
				new Expression(when);
			} catch (IllegalArgumentException e) {
				// fsmar3 evaluates a malformed condition to false at runtime,
				// which means this transition would silently never fire.
				errors.add(at + ".when does not parse (transition would never fire): "
						+ e.getMessage());
			}
		}

		String region = tx.path("region").asText("");
		if (!region.isEmpty() && !REGIONS.contains(region)) {
			errors.add(at + ".region '" + region + "' — one of: " + REGIONS);
		}

		String modifier = tx.path("routeModifier").asText("");
		if (!modifier.isEmpty() && !ROUTE_MODIFIERS.contains(modifier)) {
			errors.add(at + ".routeModifier '" + modifier + "' — one of: " + ROUTE_MODIFIERS);
		}
		// NO_ROUTE tells the container not to examine the routes array, so
		// routes + NO_ROUTE silently discards the routes (and without routes
		// NO_ROUTE is the default anyway — the without-routes case is covered
		// by the modifier-ignored warning below).
		if ("NO_ROUTE".equals(modifier) && hasRoutes) {
			warnings.add(at + " sets routes with routeModifier NO_ROUTE — the container"
					+ " ignores the routes; drop the modifier (ROUTE is the default)"
					+ " or remove the routes");
		}

		JsonNode routes = tx.path("routes");
		if (!routes.isMissingNode()) {
			if (!routes.isArray()) {
				errors.add(at + ".routes must be an array of URI strings");
			} else {
				for (JsonNode r : routes) {
					if (!r.isTextual() || r.asText().isEmpty()) {
						errors.add(at + ".routes contains a non-string or empty entry");
						break;
					}
				}
			}
		}
		if (!modifier.isEmpty() && (routes.isMissingNode() || routes.size() == 0)) {
			warnings.add(at + " sets routeModifier but has no routes — the modifier is ignored");
		}

		warnUnknown(tx, FsmarImportServlet.TRANSITION_KNOWN, at, warnings);
	}

	/// Flags fields outside the known set (plus per-call extras) as likely
	/// typos. They round-trip safely — this is advisory, not a gate.
	private static void warnUnknown(JsonNode node, Set<String> known, String at,
			List<String> warnings, String... alsoAllowed) {
		Set<String> allowed = new java.util.HashSet<>(known);
		for (String a : alsoAllowed) allowed.add(a);
		Iterator<String> names = node.fieldNames();
		while (names.hasNext()) {
			String name = names.next();
			if (!allowed.contains(name)) {
				warnings.add(at + " has unknown field '" + name
						+ "' — preserved on round-trip but ignored by the engine"
						+ " (SettingsManager reads with FAIL_ON_UNKNOWN_PROPERTIES off),"
						+ " so check it is not a typo");
			}
		}
	}

}
