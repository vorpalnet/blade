package org.vorpal.blade.applications.callflow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.vorpal.blade.framework.v3.access.Access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// A viewer's [Access] applied to what the Trace app sends: live captures,
/// recorded calls and saved calls alike, so the page is only ever sent what
/// the viewer's grants and the access policy allow.
///
/// **Which steps.** A SIP step carries the call's tenant and department as it
/// was recorded (and a recorded call's steps, its row's), and is shown when a
/// grant matches them in this domain, or the step's own domain when it names
/// one. An HTTP step is recorded where the call's session is out of reach, so
/// it carries no scope: it is shown with its call, when the viewer sees any of
/// that call's SIP steps. A department viewer sees only their own parts of a
/// call.
///
/// **Content** is asked of the policy step by step, with the step's domain,
/// tenant and department as the record, so a rule may let a department read
/// its own parts of a call and not the rest. An HTTP step's text goes with its
/// call's: shown when some SIP step of the call has its text shown. A step
/// whose text is withheld keeps its shape (time, direction, method or status,
/// source line); a recorded call loses its parties (From, To,
/// P-Asserted-Identity) unless every step shown of it keeps its text. The page
/// says the text is hidden rather than missing.
final class TraceAccess {

	/// Set on a response whose content was taken out for this viewer.
	static final String CONTENT_HIDDEN = "contentHidden";

	private final Access access;
	private final String localDomain;

	/// @param access      the viewer's access
	/// @param localDomain the domain this Trace app runs in, for steps that
	///                    name none
	TraceAccess(Access access, String localDomain) {
		this.access = access;
		this.localDomain = localDomain;
	}

	Access access() {
		return access;
	}

	/// True when the viewer's grants show every call, so nothing need be
	/// narrowed by scope.
	boolean seesAllCalls() {
		return access.seesAllCalls();
	}

	/// True when the policy lets the viewer read every call they see: what
	/// searching recorded calls by number needs.
	boolean seesContent() {
		return access.seesContent();
	}

	/// Takes out of `out.sources` every step this viewer may not see, and the
	/// text of every step whose content the policy withholds; a source left
	/// with no steps goes. Marks each such step, and `out`, [#CONTENT_HIDDEN]
	/// when text was taken out: a recorded step has no text either way, so
	/// the page tells them apart by the mark.
	void apply(ObjectNode out) {
		JsonNode sources = out.path("sources");
		if (!sources.isArray() || (access.seesAllCalls() && access.seesContent())) {
			return;
		}
		Set<String> shownCalls = new HashSet<>();
		Set<String> readCalls = new HashSet<>();
		for (JsonNode src : sources) {
			for (JsonNode step : src.path("steps")) {
				if (scoped(step) && sees(step)) {
					shownCalls.add(step.path("sessionId").asText(""));
					if (reads(step)) {
						readCalls.add(step.path("sessionId").asText(""));
					}
				}
			}
		}
		boolean hidden = false;
		ArrayNode kept = JsonNodeFactory.instance.arrayNode();
		for (JsonNode src : sources) {
			ArrayNode steps = JsonNodeFactory.instance.arrayNode();
			boolean readAll = true;
			for (JsonNode step : src.path("steps")) {
				String call = step.path("sessionId").asText("");
				boolean shown = scoped(step) ? sees(step) : shownCalls.contains(call);
				if (shown) {
					boolean read = scoped(step) ? reads(step) : readCalls.contains(call);
					ObjectNode copy = ((ObjectNode) step).deepCopy();
					if (!read) {
						copy.putNull("message");
						copy.put(CONTENT_HIDDEN, true);
						hidden = true;
						readAll = false;
					}
					steps.add(copy);
				}
			}
			if (steps.size() > 0) {
				ObjectNode copy = ((ObjectNode) src).deepCopy();
				copy.set("steps", steps);
				if (!readAll) {
					copy.remove("parties");
				}
				kept.add(copy);
			}
		}
		out.set("sources", kept);
		if (hidden) {
			out.put(CONTENT_HIDDEN, true);
		}
	}

	/// True when this viewer sees every step of a saved call, and its text, so
	/// they may replace or delete it: a narrower viewer must not overwrite the
	/// call with the part of it they were shown.
	boolean seesAll(JsonNode trace) {
		Set<String> shownCalls = new HashSet<>();
		Set<String> calls = new HashSet<>();
		for (JsonNode src : trace.path("sources")) {
			for (JsonNode step : src.path("steps")) {
				if (scoped(step)) {
					if (!sees(step) || !reads(step)) {
						return false;
					}
					shownCalls.add(step.path("sessionId").asText(""));
				}
				calls.add(step.path("sessionId").asText(""));
			}
		}
		return shownCalls.containsAll(calls);
	}

	/// True when a saved call with these scopes (from its summary,
	/// [#scopesOf]) has a part this viewer sees. A summary written before
	/// scopes were kept has none, and reads as a call with no tenant.
	boolean seesAny(JsonNode scopes) {
		if (scopes == null || !scopes.isArray() || scopes.size() == 0) {
			return access.seesCall(localDomain, null, null);
		}
		for (JsonNode s : scopes) {
			if (access.seesCall(text(s.get(0), localDomain), text(s.get(1), null), text(s.get(2), null))) {
				return true;
			}
		}
		return false;
	}

	/// The distinct `[domain, tenant, department]` of a call's SIP steps, for
	/// its saved summary, so the list of saved calls can be narrowed without
	/// reading every call.
	static ArrayNode scopesOf(JsonNode sources, String localDomain) {
		Set<String> seen = new HashSet<>();
		ArrayNode out = JsonNodeFactory.instance.arrayNode();
		for (JsonNode src : sources) {
			for (JsonNode step : src.path("steps")) {
				if (!scoped(step)) {
					continue;
				}
				String domain = text(step.get("domain"), localDomain);
				String tenant = text(step.get("tenant"), null);
				String department = text(step.get("department"), null);
				if (seen.add(domain + "\u0000" + tenant + "\u0000" + department)) {
					ArrayNode s = out.addArray();
					s.add(domain);
					s.add(tenant);
					s.add(department);
				}
			}
		}
		return out;
	}

	/// The SQL condition, starting " AND", that keeps only the recorded calls
	/// this viewer sees, its values added to `params`. `v_call_traced` names
	/// the domain `cluster_name`, as the dashboard reads it.
	String recordedWhere(List<Object> params) {
		if (access.seesAllCalls()) {
			return "";
		}
		List<Access.Rule> rules = access.rules();
		if (rules.isEmpty()) {
			return " AND 1 = 0";
		}
		StringBuilder any = new StringBuilder();
		for (Access.Rule r : rules) {
			StringBuilder all = new StringBuilder("1 = 1");
			in(all, "cluster_name", r.domains, params);
			in(all, "tenant", r.tenants, params);
			in(all, "department", r.departments, params);
			any.append(any.length() == 0 ? "" : " OR ").append('(').append(all).append(')');
		}
		return " AND (" + any + ")";
	}

	private static void in(StringBuilder sql, String column, Set<String> values, List<Object> params) {
		if (values == null) {
			return;
		}
		sql.append(" AND ").append(column).append(" IN (");
		boolean first = true;
		for (String v : values) {
			sql.append(first ? "?" : ", ?");
			params.add(v);
			first = false;
		}
		sql.append(')');
	}

	private boolean sees(JsonNode step) {
		return access.seesCall(text(step.get("domain"), localDomain), text(step.get("tenant"), null),
				text(step.get("department"), null));
	}

	private boolean reads(JsonNode step) {
		return access.seesContent(text(step.get("domain"), localDomain), text(step.get("tenant"), null),
				text(step.get("department"), null));
	}

	/// A SIP step, as against an HTTP one, which carries no scope.
	private static boolean scoped(JsonNode step) {
		return !step.path("kind").asText("").startsWith("http-");
	}

	private static String text(JsonNode node, String fallback) {
		return (node == null || node.isNull() || node.asText().isEmpty()) ? fallback : node.asText();
	}
}
