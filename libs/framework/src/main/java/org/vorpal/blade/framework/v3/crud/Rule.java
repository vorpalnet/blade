package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// One rule. Filters decide whether the rule fires for a given message;
/// `operations` is the ordered list of mutations to apply when it does.
///
/// ## Filters
///
/// All filters are `null`-tolerant — a `null` filter matches anything.
/// All four filters must pass for the rule to fire (logical AND across
/// filters). Within `method`, `event`, and `statusRange`, the syntax
/// supports OR (comma-separated tokens) and negation (`!` prefix).
///
/// | Filter        | Syntax                                                            | Examples                                       |
/// |---------------|-------------------------------------------------------------------|------------------------------------------------|
/// | `method`      | SIP method name; comma-separated for OR; `!METHOD` for negation   | `INVITE` · `INVITE,REGISTER` · `!BYE`          |
/// | `messageType` | `request` or `response`                                           | `request`                                      |
/// | `event`       | B2BUA lifecycle event; comma-separated for OR; `!event` to negate | `callStarted` · `callAnswered,callConnected`   |
/// | `statusRange` | response-only status filter (see syntax below)                    | `200-299` · `4xx` · `!5xx` · `200,301,302`     |
///
/// ### Comma + negation semantics (method, event, statusRange)
///
/// Tokens are comma-separated. Each token is positive (`INVITE`) or
/// negated (`!BYE`). The filter passes when:
///
/// - **at least one positive token matches** (or no positives are listed), AND
/// - **no negative token matches**.
///
/// So `INVITE,REGISTER` is "INVITE OR REGISTER", `!BYE` is "anything but
/// BYE", and `INVITE,!OPTIONS` is "INVITE — but never OPTIONS" (the
/// negative is redundant but harmless when the positive set already
/// excludes it).
///
/// ### `statusRange` syntax
///
/// Each token is one of:
///
/// - **Exact** — `200`
/// - **Range** — `200-299` (inclusive)
/// - **Hundred shorthand** — `4xx` ⇒ 400–499 (case-insensitive)
/// - **Negated** — `!500` or `!5xx`
///
/// Tokens combine via the same comma + negation rules above. A non-null
/// `statusRange` implicitly restricts the rule to **responses** — it
/// returns false on requests, since requests have no status.
///
/// Examples:
///
/// - `200-299` — successes only
/// - `4xx,5xx` — any error response
/// - `!200` — anything other than a 200
/// - `200,301,302,!304` — 200, 301, or 302 but never 304
///
/// ## resetVariables
///
/// `resetVariables: true` clears this rule's read variables from the
/// session before the rule fires. Useful when the same rule re-fires
/// across a long dialog and you don't want stale captures from earlier
/// matches leaking into a later create/update.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "id", "description", "method", "messageType", "event",
		"statusRange", "resetVariables", "operations" })
public class Rule implements Serializable {
	private static final long serialVersionUID = 1L;

	private String id;
	private String description;
	private String method;
	private String messageType;
	private String event;
	private String statusRange;
	private boolean resetVariables;
	private List<Operation> operations = new LinkedList<>();

	public Rule() {
	}

	/// Returns true if this rule should fire for the given message and
	/// lifecycle event. See the class javadoc for the full filter syntax.
	public boolean matches(SipServletMessage msg, String lifecycleEvent) {
		if (method != null) {
			String msgMethod = (msg instanceof SipServletRequest)
					? ((SipServletRequest) msg).getMethod()
					: ((SipServletResponse) msg).getRequest().getMethod();
			if (!matchesSpec(method, msgMethod)) return false;
		}
		if (messageType != null) {
			boolean isRequest = (msg instanceof SipServletRequest);
			if ("request".equalsIgnoreCase(messageType) && !isRequest) return false;
			if ("response".equalsIgnoreCase(messageType) && isRequest) return false;
		}
		if (event != null && lifecycleEvent != null && !matchesSpec(event, lifecycleEvent)) {
			return false;
		}
		if (statusRange != null) {
			if (!(msg instanceof SipServletResponse)) return false;
			if (!matchesStatus(statusRange, ((SipServletResponse) msg).getStatus())) return false;
		}
		return true;
	}

	/// Runs every operation against `msg` in declaration order. If
	/// `resetVariables` is set, this rule's read variables are removed from
	/// the session before the first operation runs.
	public void process(SipServletMessage msg) {
		if (resetVariables) {
			SipApplicationSession appSession = msg.getApplicationSession();
			if (appSession != null) {
				for (Operation op : operations) {
					for (String name : op.variableNames()) {
						appSession.removeAttribute(name);
					}
				}
			}
		}
		for (Operation op : operations) op.process(msg);
	}

	/// Comma-separated, OR-of-positives, AND-of-negations matcher.
	/// See class javadoc. Token comparison is case-insensitive.
	static boolean matchesSpec(String spec, String value) {
		if (spec == null) return true;
		if (value == null) return false;

		boolean hasPositive = false;
		boolean anyPositiveMatched = false;

		for (String raw : spec.split(",")) {
			String token = raw.trim();
			if (token.isEmpty()) continue;
			if (token.charAt(0) == '!') {
				String negated = token.substring(1).trim();
				if (!negated.isEmpty() && negated.equalsIgnoreCase(value)) return false;
			} else {
				hasPositive = true;
				if (token.equalsIgnoreCase(value)) anyPositiveMatched = true;
			}
		}
		return !hasPositive || anyPositiveMatched;
	}

	/// Status-code matcher. Same comma + negation rules as
	/// [#matchesSpec]; each token is `200`, `200-299`, or `4xx` (the last
	/// expands to that hundred). Malformed tokens are skipped silently.
	static boolean matchesStatus(String spec, int status) {
		if (spec == null) return true;

		boolean hasPositive = false;
		boolean anyPositiveMatched = false;

		for (String raw : spec.split(",")) {
			String token = raw.trim();
			if (token.isEmpty()) continue;
			boolean negate = token.charAt(0) == '!';
			if (negate) token = token.substring(1).trim();
			if (token.isEmpty()) continue;
			Boolean tokenMatches = matchesStatusToken(token, status);
			if (tokenMatches == null) continue; // malformed → ignore
			if (negate) {
				if (tokenMatches) return false;
			} else {
				hasPositive = true;
				if (tokenMatches) anyPositiveMatched = true;
			}
		}
		return !hasPositive || anyPositiveMatched;
	}

	/// One status token → match? Returns null on malformed input so the
	/// caller can ignore the token without failing the whole rule.
	private static Boolean matchesStatusToken(String token, int status) {
		// `Nxx` shorthand
		if (token.length() == 3
				&& Character.isDigit(token.charAt(0))
				&& (token.charAt(1) == 'x' || token.charAt(1) == 'X')
				&& (token.charAt(2) == 'x' || token.charAt(2) == 'X')) {
			int hundred = (token.charAt(0) - '0') * 100;
			return status >= hundred && status < hundred + 100;
		}
		try {
			int dash = token.indexOf('-');
			if (dash > 0) {
				int low = Integer.parseInt(token.substring(0, dash).trim());
				int high = Integer.parseInt(token.substring(dash + 1).trim());
				return status >= low && status <= high;
			}
			return Integer.parseInt(token) == status;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@JsonPropertyDescription("Unique identifier for this rule.")
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("Human-readable description shown in the configurator.")
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	@JsonPropertyDescription("SIP method filter. Single (INVITE), comma-OR (INVITE,REGISTER), or `!` to negate (!BYE). Null matches any method.")
	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	@JsonPropertyDescription("Message type filter: request or response. Null matches both.")
	public String getMessageType() {
		return messageType;
	}

	public void setMessageType(String messageType) {
		this.messageType = messageType;
	}

	@JsonPropertyDescription("Lifecycle event filter. Same syntax as method: callStarted, callAnswered,callConnected, !callCompleted. Null matches all.")
	public String getEvent() {
		return event;
	}

	public void setEvent(String event) {
		this.event = event;
	}

	@JsonPropertyDescription("Status code filter for responses, e.g. 200, 200-299, 4xx, !5xx. Implicitly restricts to responses. Null matches any status.")
	public String getStatusRange() {
		return statusRange;
	}

	public void setStatusRange(String statusRange) {
		this.statusRange = statusRange;
	}

	@JsonPropertyDescription("Clear this rule's read variables from the session before running. Default false.")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	public boolean isResetVariables() {
		return resetVariables;
	}

	public void setResetVariables(boolean resetVariables) {
		this.resetVariables = resetVariables;
	}

	@JsonPropertyDescription("Ordered list of operations: read / create / update / delete and the xml/json/sdp variants.")
	public List<Operation> getOperations() {
		return operations;
	}

	public void setOperations(List<Operation> operations) {
		this.operations = operations;
	}
}
