package org.vorpal.blade.framework.v3.fsmar;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v3.configuration.expressions.Expression;
import org.vorpal.blade.framework.v3.configuration.selectors.AttributeSelector;
import org.vorpal.blade.framework.v3.configuration.selectors.RegexSelector;
import org.vorpal.blade.framework.v3.configuration.selectors.Selector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Converts an FSMAR 2 configuration (`fsmar2.json`) to FSMAR 3 format.
///
/// Reads the old JSON generically (no dependency on the fsmar2 module) and
/// builds a real [AppRouterConfiguration], so the output is round-trip
/// validated against the same classes the FSMAR 3 engine loads. Every `when`
/// expression the converter emits is compiled with the engine's [Expression]
/// parser before the file is written.
///
/// ## Structure mapping
///
/// | FSMAR 2                          | FSMAR 3                                |
/// |----------------------------------|----------------------------------------|
/// | `previous` map                   | `states` map (same keys, `"null"` etc.)|
/// | `defaultApplication`             | `defaultApplication`                   |
/// | trigger / ordered transitions    | same (order preserved)                 |
/// | `action.originating = "From"`    | `subscriber="From"`, `region=ORIGINATING` |
/// | `action.terminating = "To"`      | `subscriber="To"`, `region=TERMINATING`|
/// | `action.route`                   | `routes` (ROUTE is the v3 default)     |
/// | `action.route_back`              | `routes` + `routeModifier=ROUTE_BACK`  |
/// | `action.route_final`             | `routes` + `routeModifier=ROUTE_FINAL` |
/// | `condition` per-header operators | per-state selectors + a `when` clause  |
///
/// ## Condition mapping
///
/// FSMAR 2 ANDs every comparison in a condition; the converter emits one
/// `&&`-joined `when`. Per operator (header `H`, expression `E`):
///
/// - `user` / `host` — one shared address [RegexSelector] on `H`; clause on
///   `${H.user}` / `${H.host}`.
/// - `equals` — [AttributeSelector] on `H`; clause on `${H}`.
/// - `matches` / `address` — [AttributeSelector]; `${H} matches 'E'` (both
///   sides are Java full-string regex — exact semantic match).
/// - `contains` — all-instances [AttributeSelector] (reads every instance of a
///   repeating header, joined); `${H__all} contains 'E'` — `contains` over the
///   joined value is true iff ANY instance contains E, matching FSMAR 2.
/// - `uri` — [RegexSelector] extracting the bracketed URI; `${H.uri} matches 'E'`.
/// - `value` — all-instances [AttributeSelector]; `${H__all} matches '(?i)E(?:;.*)?'`
///   — existential `matches` so ANY instance whose value-part equals E fires
///   (FSMAR 2 scanned every instance, case-insensitively).
/// - `includes` — converted like `equals`, flagged REVIEW (the FSMAR 2
///   implementation of `includes` did not reliably test what it claimed to).
/// - any other operator name `P` — FSMAR 2 treated it as a parameter name,
///   scanned across every instance; all-instances [AttributeSelector] +
///   `${H__all} matches '(?i).*[;]P=E(?:;.*)?'` (existential), flagged NOTE.
/// - special "headers" `Request-URI`, `Directive`, `Region`, `Region-Label` map
///   to the `${requestURI}` / `${directive}` / `${region}` / `${regionLabel}`
///   pseudo-variables (`Request-URI` `user`/`host`/param still get a selector,
///   which reads the `requestURI` pseudo-header natively).
///
/// Repeating headers (any instance matches) ride the all-instances selector
/// mode + the `matches` operator's existential semantics over the joined value
/// — see [org.vorpal.blade.framework.v3.configuration.Context#MULTI_VALUE_DELIMITER].
///
/// FSMAR 2 compared with `equalsIgnoreCase`; where `E` contains letters the
/// converter emits `matches '(?i)…'` to preserve case-insensitivity, and a
/// clean `==` otherwise (digits, IPs, dotted numbers). A `'` in E is escaped
/// (`\'`) for the Expression string literal.
///
/// ## Fail closed
///
/// Anything that cannot be converted faithfully emits `when: "false"` — the
/// transition can never fire, so a conversion gap NARROWS routing (traffic
/// falls through to later transitions or `defaultApplication`) instead of
/// silently widening it — plus a `REVIEW:` warning naming the spot.
///
/// ## CLI
///
/// ```
/// java -cp blade-fsmar.jar \
///      org.vorpal.blade.framework.v3.fsmar.Fsmar2Converter fsmar2.json fsmar3.json
/// ```
///
/// Exit 0 = clean; 1 = converted but `REVIEW:` items need human eyes;
/// 2 = could not convert.
public final class Fsmar2Converter {

	/// Full-match (DOTALL) address pattern: optional display name and angle
	/// brackets, captures `proto`, optional `user`, `host` (without port),
	/// optional `port`, and the URI `params`.
	static final String ADDRESS_PATTERN = "(?:\\s*\"[^\"]*\"\\s*)?<?\\s*(?<proto>sips?):"
			+ "(?:(?<user>[^@;>\\s]+)@)?(?<host>[^:;>\\s]+)(?::(?<port>\\d+))?(?<params>[^>]*)>?.*";

	/// Full-match pattern extracting the bracketed URI of an address header.
	static final String URI_PATTERN = "[^<]*<?(?<uri>sips?:[^>\\s]*)>?.*";

	public static final class Result {
		public final AppRouterConfiguration config = new AppRouterConfiguration();
		public final List<String> warnings = new ArrayList<>();
		public int states;
		public int transitions;
		public int selectors;

		public boolean needsReview() {
			return warnings.stream().anyMatch(w -> w.startsWith("REVIEW"));
		}
	}

	private Fsmar2Converter() {
	}

	public static Result convert(JsonNode root) {
		Result result = new Result();

		JsonNode def = root.get("defaultApplication");
		if (def != null && !def.isNull()) {
			result.config.setDefaultApplication(def.asText());
		}

		JsonNode previous = root.get("previous");
		if (previous == null || !previous.isObject()) {
			result.warnings.add("REVIEW: no 'previous' map found — is this an fsmar2 configuration?");
			return result;
		}

		Iterator<Map.Entry<String, JsonNode>> stateItr = previous.fields();
		while (stateItr.hasNext()) {
			Map.Entry<String, JsonNode> stateEntry = stateItr.next();
			String stateName = stateEntry.getKey();
			State state3 = new State();
			result.config.getStates().put(stateName, state3);
			result.states++;

			// Selectors are per-state and shared across this state's
			// transitions; dedup by identity key, insertion order preserved.
			Map<String, Selector> selectors = new LinkedHashMap<>();

			JsonNode triggers = stateEntry.getValue().get("triggers");
			if (triggers == null || !triggers.isObject()) {
				continue;
			}
			Iterator<Map.Entry<String, JsonNode>> trigItr = triggers.fields();
			while (trigItr.hasNext()) {
				Map.Entry<String, JsonNode> trigEntry = trigItr.next();
				String method = trigEntry.getKey();
				Trigger trigger3 = new Trigger();
				state3.getTriggers().put(method, trigger3);

				JsonNode transitions = trigEntry.getValue().get("transitions");
				if (transitions == null || !transitions.isArray()) {
					continue;
				}
				for (JsonNode t2 : transitions) {
					trigger3.getTransitions().add(
							convertTransition(t2, stateName, method, selectors, result));
					result.transitions++;
				}
			}

			state3.setSelectors(new ArrayList<>(selectors.values()));
			result.selectors += selectors.size();
		}

		return result;
	}

	private static Transition convertTransition(JsonNode t2, String stateName, String method,
			Map<String, Selector> selectors, Result result) {

		Transition t3 = new Transition();
		String where = "state '" + stateName + "' " + method + " transition '"
				+ text(t2, "id") + "'";

		t3.setId(text(t2, "id"));
		t3.setNext(text(t2, "next"));

		// --- action -> subscriber / region / routes ---
		JsonNode action = t2.get("action");
		if (action != null && action.isObject()) {
			String originating = text(action, "originating");
			String terminating = text(action, "terminating");
			if (originating != null) {
				t3.setSubscriber(originating);
				t3.setRegion(Transition.Region.ORIGINATING);
				if (terminating != null) {
					result.warnings.add("REVIEW: " + where + " sets both originating and terminating;"
							+ " kept originating (fsmar2 precedence).");
				}
			} else if (terminating != null) {
				t3.setSubscriber(terminating);
				t3.setRegion(Transition.Region.TERMINATING);
			}

			String[] route = strings(action, "route");
			String[] routeBack = strings(action, "route_back");
			String[] routeFinal = strings(action, "route_final");
			if (route != null) {
				t3.setRoutes(route); // ROUTE is the v3 default when routes are present
				if (routeBack != null || routeFinal != null) {
					result.warnings.add("REVIEW: " + where + " sets multiple route kinds;"
							+ " kept 'route' (fsmar2 precedence).");
				}
			} else if (routeBack != null) {
				t3.setRouteBack(routeBack);
				if (routeFinal != null) {
					result.warnings.add("REVIEW: " + where + " sets multiple route kinds;"
							+ " kept 'route_back' (fsmar2 precedence).");
				}
			} else if (routeFinal != null) {
				t3.setRouteFinal(routeFinal);
			}
		}

		// --- condition -> selectors + when ---
		JsonNode condition = t2.get("condition");
		if (condition == null || condition.isNull()) {
			return t3; // unconditional, same in both models
		}
		if (!condition.isObject()) {
			failClosed(t3, result, where, "condition is not an object");
			return t3;
		}

		List<String> clauses = new ArrayList<>();
		Iterator<Map.Entry<String, JsonNode>> condItr = condition.fields();
		while (condItr.hasNext()) {
			Map.Entry<String, JsonNode> headerEntry = condItr.next();
			String header = headerEntry.getKey();
			JsonNode comparisons = headerEntry.getValue();
			if (!comparisons.isArray()) {
				failClosed(t3, result, where, "comparisons for '" + header + "' are not an array");
				return t3;
			}
			for (JsonNode comparison : comparisons) {
				Iterator<Map.Entry<String, JsonNode>> opItr = comparison.fields();
				while (opItr.hasNext()) {
					Map.Entry<String, JsonNode> opEntry = opItr.next();
					String clause = convertComparison(header, opEntry.getKey(),
							opEntry.getValue().asText(), where, selectors, result);
					if (clause == null) {
						failClosed(t3, result, where, "operator '" + opEntry.getKey()
								+ "' on '" + header + "' could not be converted");
						return t3;
					}
					clauses.add(clause);
				}
			}
		}
		if (!clauses.isEmpty()) {
			t3.setWhen(String.join(" && ", clauses));
		}
		return t3;
	}

	/// Returns the `when` clause for one fsmar2 comparison, registering any
	/// selector it needs, or null if it cannot be converted faithfully.
	private static String convertComparison(String header, String op, String expr, String where,
			Map<String, Selector> selectors, Result result) {

		if (expr == null) {
			return null; // nothing to compare against
		}

		// Special fsmar2 "header" names backed by v3 pseudo-variables.
		switch (header) {
		case "Directive":
			return eq("${directive}", expr);
		case "Region":
			return eq("${region}", expr);
		case "Region-Label":
			return eq("${regionLabel}", expr);
		case "Request-URI":
			switch (op) {
			case "matches":
			case "address":
				return "${requestURI} matches '" + litEscape(expr) + "'";
			case "equals":
				return eq("${requestURI}", expr);
			// user/host/params fall through to the selector paths below;
			// readSource resolves the "Request-URI" attribute natively.
			}
			break;
		}

		switch (op) {
		case "user":
		case "host":
			addressSelector(header, selectors);
			return eq("${" + key(header) + "." + op + "}", expr);

		case "equals":
			attributeSelector(header, selectors);
			return eq("${" + key(header) + "}", expr);

		case "includes":
			attributeSelector(header, selectors);
			result.warnings.add("REVIEW: " + where + " used fsmar2 'includes' on '" + header
					+ "', whose semantics were unreliable; converted as 'equals' — verify intent.");
			return eq("${" + key(header) + "}", expr);

		case "matches":
		case "address":
			attributeSelector(header, selectors);
			return "${" + key(header) + "} matches '" + litEscape(expr) + "'";

		case "contains":
			// fsmar2 scanned EVERY instance of a repeating header; the
			// all-instances selector joins them so `contains` (a substring test
			// over the joined value) is true iff any instance contains expr.
			attributeSelectorAll(header, selectors);
			return "${" + allId(header) + "} contains '" + litEscape(expr) + "'";

		case "uri":
			regexSelector(header, URI_PATTERN, selectors);
			result.warnings.add("NOTE: " + where + " 'uri' on '" + header
					+ "' converted via a URI-extraction regex; eyeball against real traffic.");
			return "${" + key(header) + ".uri} matches '" + litEscape(expr) + "'";

		case "value":
			// fsmar2 matched the value-part (before ';') of ANY instance,
			// case-insensitively. `matches` over the all-instances value is
			// existential, so a per-element regex `expr(;params)?` does the same.
			attributeSelectorAll(header, selectors);
			return "${" + allId(header) + "} matches '"
					+ litEscape("(?i)" + escapeRegex(expr) + "(?:;.*)?") + "'";

		default:
			// fsmar2 treated an unknown operator as a parameter name, scanned
			// across every instance of the (repeating) header.
			if (!op.matches("[a-zA-Z][a-zA-Z0-9]*")) {
				return null; // not a legal parameter name
			}
			attributeSelectorAll(header, selectors);
			result.warnings.add("NOTE: " + where + " parameter '" + op + "' of '" + header
					+ "' converted via an any-instance parameter-match regex; eyeball against real traffic.");
			// Full-element (matches) regex: any chars, then ;param=value, then a
			// param boundary (`;`, `>`, `,`, whitespace) or end — so a value that
			// closes an angle-bracket URI (…;transport=tcp>) still matches, while
			// `tcp` does NOT match `tcpx`.
			return "${" + allId(header) + "} matches '"
					+ litEscape("(?i).*[;?]" + escapeRegex(op) + "=" + escapeRegex(expr)
							+ "(?:[;>,\\s].*)?") + "'";
		}
	}

	/// fsmar2 compared with equalsIgnoreCase. Letters present → preserve
	/// case-insensitivity with a quoted `(?i)` regex; otherwise a clean `==`.
	private static String eq(String var, String expr) {
		if (expr.chars().anyMatch(Character::isLetter)) {
			return var + " matches '" + litEscape("(?i)" + escapeRegex(expr)) + "'";
		}
		return var + " == '" + litEscape(expr) + "'";
	}

	private static void failClosed(Transition t3, Result result, String where, String why) {
		t3.setWhen("false");
		result.warnings.add("REVIEW: " + where + " — " + why
				+ "; emitted when=\"false\" so it can never fire (fail closed). Convert by hand.");
	}

	private static void addressSelector(String header, Map<String, Selector> selectors) {
		selectors.computeIfAbsent("regex|" + header + "|" + ADDRESS_PATTERN,
				k -> new RegexSelector(key(header), key(header), ADDRESS_PATTERN, null));
	}

	private static void regexSelector(String header, String pattern, Map<String, Selector> selectors) {
		selectors.computeIfAbsent("regex|" + header + "|" + pattern,
				k -> new RegexSelector(key(header), key(header), pattern, null));
	}

	private static void attributeSelector(String header, Map<String, Selector> selectors) {
		selectors.computeIfAbsent("attribute|" + header,
				k -> new AttributeSelector(key(header), key(header)));
	}

	/// All-instances variant: reads every instance of a repeating header (joined
	/// by [org.vorpal.blade.framework.v3.configuration.Context#MULTI_VALUE_DELIMITER])
	/// so a `contains`/`matches` clause tests *any* instance — fsmar2's
	/// scan-every-instance behavior for `contains`/`value`/parameter operators.
	/// Distinct id ([#allId]) and dedup key so it coexists with a first-instance
	/// selector for the same header in the same state.
	private static void attributeSelectorAll(String header, Map<String, Selector> selectors) {
		selectors.computeIfAbsent("attribute-all|" + header,
				k -> new AttributeSelector(allId(header), key(header), true));
	}

	/// Context id for the all-instances reading of a header — distinct from the
	/// first-instance [#key] so both can be present in one state.
	private static String allId(String header) {
		return key(header) + "__all";
	}

	/// Escapes a value for an Expression single-quoted string literal: only the
	/// quote needs escaping (`'` → `\'`). The Expression parser passes every
	/// other backslash through verbatim, so regex patterns are unaffected.
	private static String litEscape(String s) {
		return s.replace("'", "\\'");
	}

	/// Maps an fsmar2 "header" name to its v3 canonical name (used as both the
	/// selector id and the attribute it reads). Only `Request-URI` differs: it
	/// is the `requestURI` pseudo-header in v3 (the dash would also split oddly
	/// in `${}` names). Real SIP headers pass through unchanged.
	private static String key(String header) {
		return header.equals("Request-URI") ? "requestURI" : header;
	}

	private static String escapeRegex(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 8);
		for (char c : s.toCharArray()) {
			if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.toString();
	}

	private static String text(JsonNode node, String field) {
		JsonNode n = node.get(field);
		return (n != null && !n.isNull()) ? n.asText() : null;
	}

	private static String[] strings(JsonNode node, String field) {
		JsonNode n = node.get(field);
		if (n == null || !n.isArray() || n.size() == 0) {
			return null;
		}
		String[] out = new String[n.size()];
		for (int i = 0; i < n.size(); i++) {
			out[i] = n.get(i).asText();
		}
		return out;
	}

	/// Serialize just the converted routing model (defaultApplication +
	/// states), round-trip it through the engine's own configuration classes,
	/// and compile every `when` with the engine's Expression parser.
	public static String toValidatedJson(Result result) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
		ObjectNode tree = mapper.valueToTree(result.config);
		tree.retain("defaultApplication", "states");
		String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);

		AppRouterConfiguration reloaded = mapper.readValue(json, AppRouterConfiguration.class);
		for (Map.Entry<String, State> se : reloaded.getStates().entrySet()) {
			for (Map.Entry<String, Trigger> te : se.getValue().getTriggers().entrySet()) {
				for (Transition t : te.getValue().getTransitions()) {
					if (t.getWhen() != null && !t.getWhen().isEmpty()) {
						new Expression(t.getWhen()); // throws on parse error
					}
				}
			}
		}
		return json;
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("Usage: Fsmar2Converter <fsmar2-in.json> <fsmar3-out.json>");
			System.exit(2);
		}
		ObjectMapper mapper = new ObjectMapper();
		Result result;
		String json;
		try {
			result = convert(mapper.readTree(new File(args[0])));
			json = toValidatedJson(result);
		} catch (Exception e) {
			System.err.println("Conversion failed: " + e);
			System.exit(2);
			return;
		}

		java.nio.file.Files.write(new File(args[1]).toPath(), json.getBytes("UTF-8"));

		System.out.println("Converted " + args[0] + " -> " + args[1]);
		System.out.println("  states=" + result.states + " transitions=" + result.transitions
				+ " selectors=" + result.selectors);
		for (String w : result.warnings) {
			System.out.println("  " + w);
		}
		if (result.needsReview()) {
			System.out.println("REVIEW items above need human eyes before deploying.");
			System.exit(1);
		}
		System.out.println("Clean conversion; output validated against FSMAR 3 classes.");
	}

}
