package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.config.FormLayout;
import org.vorpal.blade.framework.v3.configuration.expressions.Expression;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
/// | `when`        | boolean expression over session variables (Expression grammar)    | `${customerTier} == premium` · `${a} && ${b}`  |
/// | `leg`         | the leg the message is on: `caller` or `callee`                   | `callee`                                       |
/// | `sdp`         | the message carries an SDP `offer` or an SDP `answer`             | `offer`                                        |
///
/// In an application's own `crud` section ([Crud]) a rule sees the event
/// `send` for every message the application sends, and runs then when it
/// leaves `event` unset. It sees `receive` for every message the application
/// receives, before the application handles it, and runs then only when its
/// `event` names `receive`: changing what arrives is a deliberate choice.
///
/// `leg` tells the two sides of a B2BUA apart. The caller's leg is the
/// session the initial request arrived on; every other session is a callee's.
/// A response to the caller is on the caller's leg, as is a BYE to the caller.
///
/// `sdp` follows the offer/answer model (RFC 3264) rather than the method,
/// so one rule covers an offer in an INVITE, a re-INVITE, an UPDATE, or a 2xx
/// to an INVITE that had none (a late offer), and its answer wherever that
/// travels, a 2xx, an ACK or a PRACK. See [#sdpRole].
///
/// ## Rules are all-or-nothing
///
/// If an operation fails, the rule is undone: the message, its session
/// variables and anything pending go back to what they were before the rule
/// ran, and the next rule runs as usual ([MessageSnapshot]). An operation
/// that finds nothing to do (no such header, an unset `${name}`) is not a
/// failure.
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
@JsonPropertyOrder({ "description", "enabled", "method", "messageType", "event",
		"leg", "sdp", "statusRange", "when", "resetVariables", "operations" })
public class Rule implements Serializable {
	/// 2: rules keyed by id, enum filters, `enabled` (2026-11).
	private static final long serialVersionUID = 2L;

	/// The side of a B2BUA a message is on.
	public enum Leg {
		/// The session the initial request arrived on.
		caller,
		/// Any other session.
		callee;

		/// Any case, as the filter was a string compared without case.
		@com.fasterxml.jackson.annotation.JsonCreator
		public static Leg of(String value) {
			if (value == null || value.trim().isEmpty()) return null; // no filter, as before
			return valueOf(value.trim().toLowerCase(java.util.Locale.ROOT));
		}
	}

	/// What the SDP in a message is, by the offer/answer model ([#sdpRole]).
	public enum SdpRole {
		offer, answer;

		/// Any case.
		@com.fasterxml.jackson.annotation.JsonCreator
		public static SdpRole of(String value) {
			if (value == null || value.trim().isEmpty()) return null;
			return valueOf(value.trim().toLowerCase(java.util.Locale.ROOT));
		}
	}

	/// Request or response.
	public enum MessageType {
		request, response;

		/// Any case: `messageType` was a string compared without case, so
		/// `"Request"` in an existing file still loads.
		@com.fasterxml.jackson.annotation.JsonCreator
		public static MessageType of(String value) {
			if (value == null || value.trim().isEmpty()) return null;
			return valueOf(value.trim().toLowerCase(java.util.Locale.ROOT));
		}
	}

	private String id;
	private Boolean enabled;
	/// This rule's hit counter and the registry it came from ([RuleSet#hit]).
	transient org.vorpal.blade.framework.v3.metrics.Counter hits;
	transient org.vorpal.blade.framework.v3.metrics.MetricsRegistry hitsIn;
	private String description;
	private String method;
	private MessageType messageType;
	private String event;
	private String statusRange;
	private String when;
	private boolean resetVariables;
	private Leg leg;
	private SdpRole sdp;
	private List<Operation> operations = new LinkedList<>();

	@JsonIgnore
	private transient Expression compiledWhen;

	@JsonIgnore
	private transient boolean whenReported;

	public Rule() {
	}

	/// Returns true if this rule should fire for the given message and
	/// lifecycle event. See the class javadoc for the full filter syntax.
	public boolean matches(SipServletMessage msg, String lifecycleEvent) {
		if (!invalid.isEmpty()) return false;
		if (method != null) {
			String msgMethod = (msg instanceof SipServletRequest)
					? ((SipServletRequest) msg).getMethod()
					: ((SipServletResponse) msg).getRequest().getMethod();
			if (!matchesSpec(method, msgMethod)) return false;
		}
		if (messageType != null) {
			boolean isRequest = (msg instanceof SipServletRequest);
			if ((messageType == MessageType.request) != isRequest) return false;
		}
		// A received message runs only rules that name `receive`: a missing
		// event, or one of negations only, means the messages it sends.
		if (Crud.RECEIVE_EVENT.equals(lifecycleEvent) && !namesReceive(event)) {
			return false;
		}
		if (event != null && lifecycleEvent != null && !matchesSpec(event, lifecycleEvent)) {
			return false;
		}
		if (leg != null && !leg.name().equals(legOf(msg))) {
			return false;
		}
		if (sdp != null && !sdp.name().equals(sdpRole(msg))) {
			return false;
		}
		if (statusRange != null) {
			if (!(msg instanceof SipServletResponse)) return false;
			// The status that will go out: an earlier rule's rewrite, if any.
			Object pending = msg.getAttribute(MessageHelper.PENDING_STATUS);
			int status = (pending instanceof Integer) ? (Integer) pending : ((SipServletResponse) msg).getStatus();
			if (!matchesStatus(statusRange, status)) return false;
		}
		if (when != null && !when.isEmpty() && !whenApplies(msg)) return false;
		return true;
	}

	/// Evaluates the `when` expression against the message's session
	/// variables. Compiles lazily; parse errors resolve to false so a bad
	/// expression never accidentally fires the rule — same policy as
	/// [org.vorpal.blade.framework.v3.configuration.routing.ConditionalHeader].
	private boolean whenApplies(SipServletMessage msg) {
		try {
			if (compiledWhen == null) compiledWhen = new Expression(when);
			return compiledWhen.evaluate(new SessionVariablesContext(
					MessageHelper.getSessionVariables(msg.getApplicationSession())));
		} catch (Exception e) {
			// Once per rule: a broken expression would otherwise log on every
			// message. Save refuses one ([#problems]); this catches a file
			// edited by hand.
			if (!whenReported) {
				whenReported = true;
				org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger().warning(msg,
						"Rule[" + id + "] - when \"" + when + "\" cannot be evaluated, so the rule never fires: "
								+ e.getMessage());
			}
			return false;
		}
	}

	/// What would go wrong only when a call reaches this rule: a `when` that
	/// does not parse, a `statusRange` token that is not a status, a regex,
	/// XPath or JsonPath that does not compile ([Operation#check]). Empty
	/// when there is none. At run time each of these fails quietly (the rule
	/// never fires, or is undone), so Save refuses them instead.
	public List<String> problems() {
		List<String> out = new java.util.ArrayList<>(invalid);
		if (unknownMessageType != null) {
			out.add("messageType: \"" + unknownMessageType + "\" is neither request nor response, so it matches both, "
					+ "as it always has; clear it or choose one");
		}
		if (when != null && !when.isEmpty()) {
			try {
				new Expression(when);
			} catch (Exception e) {
				out.add("when: " + e.getMessage());
			}
		}
		if (statusRange != null) {
			for (String raw : statusRange.split(",")) {
				String token = raw.trim();
				if (token.isEmpty()) continue; // a trailing comma: skipped at run time too
				if (token.startsWith("!")) token = token.substring(1).trim();
				if (token.isEmpty() || matchesStatusToken(token, 0) == null) {
					out.add("statusRange: \"" + raw.trim() + "\" is not a status (200), a range (200-299) or a class (4xx)");
				}
			}
		}
		boolean receiveOnly = receiveOnly(event);
		for (int i = 0; i < operations.size(); i++) {
			Operation op = operations.get(i);
			if (op == null) {
				// Lists append when files merge, so a null here is not a removal.
				out.add("operation " + (i + 1) + ": empty (null); an operation cannot be removed by a cluster or server file");
				continue;
			}
			try {
				op.check();
			} catch (Exception e) {
				out.add("operation " + (i + 1) + ": " + e.getMessage());
			}
			if (receiveOnly && writesStatus(op)) {
				out.add("operation " + (i + 1) + ": a status change takes effect on a response the application "
						+ "sends; this rule runs only on what it receives (event: " + event + ")");
			}
		}
		return out;
	}

	/// A message attribute that names the leg a message goes to when its
	/// session says otherwise: the passthru proxy forwards the caller's own
	/// INVITE to the callee.
	public static final String LEG_ATTR = "crud.leg";

	/// The caller's From tag, kept on the session at the initial request.
	public static final String CALLER_TAG = "crud.callerTag";

	/// A proxied dialog: the framework marks the application session
	/// (`isProxy`) whenever it proxies, however late the proxy started.
	private static boolean proxied(SipServletMessage msg, javax.servlet.sip.SipSession session) {
		javax.servlet.sip.SipApplicationSession app = msg.getApplicationSession();
		return app != null && Boolean.TRUE.equals(app.getAttribute("isProxy"));
	}

	/// True when `event` names `receive` itself, not only by negation.
	static boolean namesReceive(String event) {
		if (event == null) return false;
		for (String raw : event.split(",")) {
			if (raw.trim().equalsIgnoreCase(Crud.RECEIVE_EVENT)) return true;
		}
		return false;
	}

	/// True when `event` names `receive` and nothing the application sends.
	static boolean receiveOnly(String event) {
		if (event == null) return false;
		boolean receive = false;
		for (String raw : event.split(",")) {
			String t = raw.trim();
			if (t.startsWith("!")) return false;
			if (t.equalsIgnoreCase(Crud.RECEIVE_EVENT)) receive = true;
			else if (!t.isEmpty()) return false;
		}
		return receive;
	}

	private static boolean writesStatus(Operation op) {
		String attribute = (op instanceof UpdateOperation) ? ((UpdateOperation) op).getAttribute()
				: (op instanceof CreateOperation) ? ((CreateOperation) op).getAttribute() : null;
		return "status".equalsIgnoreCase(attribute) || "reasonPhrase".equalsIgnoreCase(attribute);
	}

	/// The leg `msg` is on: `caller` for the session the initial request
	/// arrived on (the baseline servlet marks it), `callee` for any other.
	static String legOf(SipServletMessage msg) {
		Object forced = msg.getAttribute(LEG_ATTR);
		if (forced instanceof String) return (String) forced;
		javax.servlet.sip.SipSession session = msg.getSession();
		// A proxy has one session for both parties: a request inside the
		// dialog is the caller's when it carries the caller's From tag.
		if (session != null && msg instanceof SipServletRequest && proxied(msg, session)) {
			Object callerTag = session.getAttribute(CALLER_TAG);
			try {
				String tag = msg.getFrom().getParameter("tag");
				if (callerTag != null && tag != null) return callerTag.equals(tag) ? "caller" : "callee";
			} catch (Exception ignore) {
				// fall back to the session
			}
		}
		return (session != null && "caller".equals(session.getAttribute("userAgent"))) ? "caller" : "callee";
	}

	/// `offer` or `answer` for a message carrying SDP, otherwise null.
	///
	/// - A response answers when its request carried SDP and offers when it
	///   did not (a late offer in a 2xx, or an offer in a reliable 1xx).
	/// - An ACK answers: it only carries SDP to answer a late offer.
	/// - A PRACK answers when the INVITE on its session carried no SDP, since
	///   the offer then came in the reliable provisional it acknowledges
	///   (RFC 3262); otherwise it offers.
	/// - Any other request offers.
	static String sdpRole(SipServletMessage msg) {
		if (!hasSdp(msg)) return null;
		if (msg instanceof SipServletResponse) {
			return hasSdp(((SipServletResponse) msg).getRequest()) ? "answer" : "offer";
		}
		switch (((SipServletRequest) msg).getMethod()) {
		case "ACK":
			return "answer";
		case "PRACK":
			try {
				// The INVITE this PRACK's session sent (a PRACK this side
				// sends) or received (a PRACK it receives).
				SipServletRequest invite = msg.getSession().getActiveInvite(javax.servlet.sip.UAMode.UAC);
				if (invite == null) invite = msg.getSession().getActiveInvite(javax.servlet.sip.UAMode.UAS);
				return (invite != null && !hasSdp(invite)) ? "answer" : "offer";
			} catch (Exception e) {
				return "offer";
			}
		default:
			return "offer";
		}
	}

	private static boolean hasSdp(SipServletMessage msg) {
		try {
			return msg != null && MessageHelper.getAttributeValue(msg, "body", "application/sdp") != null;
		} catch (Exception e) {
			return false;
		}
	}

	/// Runs every operation against `msg` in declaration order, all or
	/// nothing: if one fails the rule is undone and this returns false. If
	/// `resetVariables` is set, this rule's read variables are removed from
	/// the session before the first operation runs.
	public boolean process(SipServletMessage msg) {
		MessageSnapshot before;
		try {
			before = MessageSnapshot.of(msg);
		} catch (Exception e) {
			org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger().warning(msg,
					"Rule[" + id + "] - cannot take the message's state, so cannot undo it; not run: " + e);
			return false;
		}
		int step = 0;
		try {
			if (resetVariables) {
				SipApplicationSession appSession = msg.getApplicationSession();
				if (appSession != null) {
					for (Operation op : operations) {
						if (op == null) continue;
						for (String name : op.variableNames()) {
							MessageHelper.removeVariable(appSession, name);
						}
					}
				}
			}
			for (Operation op : operations) {
				step++;
				if (op != null) op.process(msg); // an empty entry does nothing (Save reports it)
			}
			return true;
		} catch (Exception e) {
			try {
				before.restore(msg);
				org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger().warning(msg, "Rule[" + id
						+ "] - operation " + step + " failed, rule undone: " + e);
			} catch (Exception restoring) {
				org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger().severe(msg, "Rule[" + id
						+ "] - operation " + step + " failed (" + e + ") and undoing it failed too: " + restoring);
			}
			return false;
		}
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
	/// Public because the tester's `expectFinal` reuses this syntax.
	public static boolean matchesStatus(String spec, int status) {
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

	@JsonPropertyDescription("Only messages on this leg: caller or callee. Empty matches both.")
	public Leg getLeg() {
		return leg;
	}

	public void setLeg(Leg leg) {
		this.leg = leg;
	}

	/// The `leg` a file holds. A value that is not a choice switches only
	/// this rule off (it never matches, as an unknown string never did) and
	/// is a Save problem; the rest of the configuration still loads.
	@com.fasterxml.jackson.annotation.JsonSetter("leg")
	public void readLeg(String value) {
		this.leg = choice(value, Leg::of, "leg");
	}

	@JsonPropertyDescription("Only messages carrying an SDP offer, or an SDP answer (RFC 3264). Empty matches any message.")
	public SdpRole getSdp() {
		return sdp;
	}

	public void setSdp(SdpRole sdp) {
		this.sdp = sdp;
	}

	/// The `sdp` a file holds ([#readLeg]).
	@com.fasterxml.jackson.annotation.JsonSetter("sdp")
	public void readSdp(String value) {
		this.sdp = choice(value, SdpRole::of, "sdp");
	}

	/// The rule's key in its rule set's `rules` ([RuleSet#setRules]), for log
	/// lines and the preview. Not written in the rule itself.
	@JsonIgnore
	public String getId() {
		return id;
	}

	@JsonIgnore
	public void setId(String id) {
		this.id = id;
	}

	@JsonPropertyDescription("False switches the rule off. A cluster or server file uses it to turn off a rule it inherits. Default true.")
	public Boolean getEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

	/// False only when [#getEnabled] is false.
	public boolean runs() {
		return !Boolean.FALSE.equals(enabled);
	}

	@JsonPropertyDescription("What this rule is for, in a sentence. Shown in the editor; no effect on calls.")
	@FormLayout(wide = true)
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
	public MessageType getMessageType() {
		return messageType;
	}

	public void setMessageType(MessageType messageType) {
		this.messageType = messageType;
	}

	/// The `messageType` a file holds ([#readLeg]).
	@com.fasterxml.jackson.annotation.JsonSetter("messageType")
	public void readMessageType(String value) {
		// Unlike `leg` and `sdp`, an unknown `messageType` always matched both
		// requests and responses: it still does, and Save asks for it to go.
		unknownMessageType = null;
		try {
			this.messageType = MessageType.of(value);
		} catch (IllegalArgumentException notAChoice) {
			this.messageType = null;
			unknownMessageType = value;
		}
	}

	/// A `messageType` that is neither choice; matches both, as it always did.
	@com.fasterxml.jackson.annotation.JsonIgnore
	private transient String unknownMessageType;

	/// Filters whose value was not one of their choices; the rule never
	/// matches while there is one.
	private final java.util.List<String> invalid = new java.util.ArrayList<>();

	private <E> E choice(String value, java.util.function.Function<String, E> of, String name) {
		invalid.removeIf(p -> p.startsWith(name + ":"));
		try {
			return of.apply(value);
		} catch (IllegalArgumentException notAChoice) {
			invalid.add(name + ": \"" + value + "\" is not one of its choices; this rule is off until it is");
			return null;
		}
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

	@FormLayout(wide = true)
	@JsonPropertyDescription("Boolean expression over session variables gating this rule, e.g. ${customerTier} == premium. Comparisons (== != < <= > >=), matches/contains/insubnet, and && || ! combinators. Null matches always; a malformed expression never matches.")
	public String getWhen() {
		return when;
	}

	public void setWhen(String when) {
		this.when = when;
		this.compiledWhen = null;
		this.whenReported = false;
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
