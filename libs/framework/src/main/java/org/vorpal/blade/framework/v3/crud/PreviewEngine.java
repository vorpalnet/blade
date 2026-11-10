package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.configuration.Context;

/// Glues [SipMessageParser] → rule application → [SipMessageSerializer]
/// into a single dry-run pass. The "try-it" sandbox in the configurator
/// posts here.
///
/// Stateless and side-effect-free apart from mutating the parsed-in-memory
/// message; nothing touches the wire or any real session.
public class PreviewEngine implements Serializable {
	private static final long serialVersionUID = 1L;

	private PreviewEngine() {
	}

	/// One dry-run pass with no pre-populated session state.
	public static PreviewResult preview(CrudConfiguration config, String ruleSetId,
			String messageText, String lifecycleEvent) {
		return preview(config, ruleSetId, messageText, lifecycleEvent, null);
	}

	/// One dry-run pass.
	///
	/// @param config             the deployed CRUD configuration (for rule-set lookup)
	/// @param ruleSetId          which rule set to apply
	/// @param messageText        the raw SIP wire text
	/// @param lifecycleEvent     `callStarted` / `callAnswered` / etc.; may be null
	///                           to skip event-based filters
	/// @param initialVariables   name → value pairs pre-loaded onto the session
	///                           before any rule runs. Use this to simulate
	///                           values that the enrichment pipeline would
	///                           have written, or to override an environment
	///                           variable for the run. May be null.
	public static PreviewResult preview(CrudConfiguration config, String ruleSetId,
			String messageText, String lifecycleEvent, Map<String, String> initialVariables) {
		return preview(config, ruleSetId, messageText, lifecycleEvent, initialVariables, null, null);
	}

	/// One dry-run pass, placed in a call.
	///
	/// A pasted message carries no call around it, yet the `leg` and `sdp`
	/// filters read the call: which side the message is on, and whether a
	/// response's request carried SDP. These two say so.
	///
	/// @param leg         `caller` or `callee`; null takes the side the
	///                    message is on in a B2BUA: a sent request goes to the
	///                    callee and a sent response to the caller, and the
	///                    other way round for the `receive` event
	/// @param requestSdp  for a response, whether its request carried SDP, so
	///                    SDP in the response is an answer; null means it did
	public static PreviewResult preview(CrudConfiguration config, String ruleSetId,
			String messageText, String lifecycleEvent, Map<String, String> initialVariables,
			String leg, Boolean requestSdp) {
		PreviewResult result = new PreviewResult();
		result.lifecycleEvent = lifecycleEvent;

		if (config == null) {
			result.error = "no CrudConfiguration loaded";
			return result;
		}
		if (ruleSetId == null || ruleSetId.isEmpty()) {
			result.error = "ruleSet id is required";
			return result;
		}
		RuleSet ruleSet = config.getRuleSets().get(ruleSetId);
		if (ruleSet == null) {
			result.error = "no rule set named '" + ruleSetId + "'";
			return result;
		}

		SipServletMessage msg;
		try {
			msg = SipMessageParser.parse(messageText);
		} catch (Exception e) {
			result.error = "failed to parse SIP message: " + e.getMessage();
			return result;
		}

		// The configuration's variables sit beneath the call, where the
		// engines read them, so a reset or an undo falls back to them alike.
		defaults(msg.getApplicationSession(), config.getVariables());
		// Pre-populate the session with operator-supplied variables — this
		// stands in for the enrichment pipeline that would have run before
		// this rule set in production, and lets operators override env vars
		// for the dry run.
		if (initialVariables != null && !initialVariables.isEmpty()) {
			SipApplicationSession appSession = msg.getApplicationSession();
			if (appSession != null) {
				for (Map.Entry<String, String> e : initialVariables.entrySet()) {
					if (e.getKey() != null && e.getValue() != null) {
						MessageHelper.putVariable(appSession, e.getKey(), e.getValue());
					}
				}
			}
		}

		place(msg, lifecycleEvent, leg, requestSdp);
		result.leg = Rule.legOf(msg);
		result.sdp = Rule.sdpRole(msg);

		// The rules, as RuleSet.applyRules runs them, recording each that
		// matched and whether it applied or was undone.
		for (Rule rule : ruleSet.getRules().values()) {
			if (rule.runs() && rule.matches(msg, lifecycleEvent)) {
				(rule.process(msg) ? result.rulesFired : result.rulesUndone).add(rule.getId());
			}
		}

		Object refusal = msg.getAttribute(MessageHelper.PENDING_REJECT);
		if (refusal instanceof RejectOperation.Refusal) {
			RejectOperation.Refusal r = (RejectOperation.Refusal) refusal;
			result.rejected = r.status + ((r.reasonPhrase != null && !r.reasonPhrase.isEmpty()) ? " " + r.reasonPhrase : "");
		}
		result.output = SipMessageSerializer.serialize(msg);
		result.variables = snapshotVariables(msg.getApplicationSession());
		return result;
	}

	/// Gives the parsed message the call around it that the `leg` and `sdp`
	/// filters read.
	private static void place(SipServletMessage msg, String lifecycleEvent, String leg, Boolean requestSdp) {
		boolean request = msg instanceof SipServletRequest;
		if (leg == null || leg.isEmpty()) {
			boolean received = Crud.RECEIVE_EVENT.equals(lifecycleEvent);
			leg = (request != received) ? "callee" : "caller";
		}
		if (msg.getSession() != null && "caller".equalsIgnoreCase(leg)) {
			msg.getSession().setAttribute("userAgent", "caller");
		}
		if (!request && !Boolean.FALSE.equals(requestSdp)) {
			try {
				((javax.servlet.sip.SipServletResponse) msg).getRequest()
						.setContent("v=0\r\n", "application/sdp");
			} catch (Exception e) {
				// the response's SDP then reads as an offer
			}
		}
	}

	/// Dry-runs the enrichment pipeline against a pasted SIP request and
	/// reports which rule set it selects — the selection-side counterpart
	/// of [#preview]. Parse → seed variables → [CrudConfiguration#enrich]
	/// → [CrudConfiguration#selectedRuleSet]. A null `ruleSet` in the
	/// result means passthrough (no match and no `defaultRuleSet`); an
	/// unknown-id selection also comes back null, with the engine's
	/// warning surfaced through the caller's capturing logger.
	///
	/// @param config             the deployed CRUD configuration
	/// @param messageText        the raw SIP wire text; must be a request
	/// @param initialVariables   name → value pairs pre-loaded onto the
	///                           session before the pipeline runs. May be null.
	public static SelectionResult selectionPreview(CrudConfiguration config, String messageText,
			Map<String, String> initialVariables) {
		SelectionResult result = new SelectionResult();

		if (config == null) {
			result.error = "no CrudConfiguration loaded";
			return result;
		}

		SipServletMessage msg;
		try {
			msg = SipMessageParser.parse(messageText);
		} catch (Exception e) {
			result.error = "failed to parse SIP message: " + e.getMessage();
			return result;
		}
		if (!(msg instanceof SipServletRequest)) {
			result.error = "selection runs on initial requests — paste a SIP request, not a response";
			return result;
		}
		SipServletRequest request = (SipServletRequest) msg;

		if (initialVariables != null && !initialVariables.isEmpty()) {
			SipApplicationSession appSession = request.getApplicationSession();
			if (appSession != null) {
				for (Map.Entry<String, String> e : initialVariables.entrySet()) {
					if (e.getKey() != null && e.getValue() != null) {
						MessageHelper.putVariable(appSession, e.getKey(), e.getValue());
					}
				}
			}
		}

		// The inputs sit beneath the call as the file's variables do, where
		// the pipeline and the selection read them (Context.get looks up bare
		// names). config is this request's own copy.
		if (initialVariables != null && !initialVariables.isEmpty()) {
			Map<String, String> vars = new java.util.LinkedHashMap<>(config.getVariables());
			vars.putAll(initialVariables);
			config.setVariables(vars);
		}
		Context ctx = config.enriches() ? config.enrich(request) : new Context(request, config.getVariables());
		RuleSet ruleSet = config.selectedRuleSet(ctx);
		result.ruleSet = (ruleSet != null) ? ruleSet.getId() : null;
		defaults(request.getApplicationSession(), config.getVariables());
		result.variables = snapshotVariables(request.getApplicationSession());
		return result;
	}

	/// Names `variables` as the configuration variables the call resolves
	/// beneath ([MessageHelper#DEFAULTS]).
	private static void defaults(SipApplicationSession appSession, Map<String, String> variables) {
		if (appSession != null) appSession.setAttribute(MessageHelper.DEFAULTS, new java.util.HashMap<>(variables));
	}

	/// Snapshot the string-typed attributes on the application session at
	/// end of a preview pass. These are the variables produced by `read`-
	/// flavoured operations and consumed by `${var}` substitution in
	/// downstream `create` / `update` ops; surfacing them in the UI shows
	/// operators what their reads actually captured.
	private static Map<String, String> snapshotVariables(SipApplicationSession appSession) {
		return new LinkedHashMap<>(MessageHelper.getSessionVariables(appSession));
	}

	/// Result of a dry-run preview. Either `error` is set (parse / config
	/// error) or `output` / `rulesFired` / `variables` describe the
	/// transformed message and the session state it leaves behind. The
	/// `warnings` list is populated by callers that bracket the preview
	/// with a logger that captures per-request log events; the
	/// AdminServer-side preview servlet does this so a rule undone, or an
	/// operation skipped for an unset variable, shows in the UI.
	public static class PreviewResult implements Serializable {
		private static final long serialVersionUID = 1L;
		public String lifecycleEvent;
		/// Rules that matched and applied.
		public List<String> rulesFired = new ArrayList<>();
		/// Rules that matched, failed partway, and were undone; the warnings
		/// say which operation failed.
		public List<String> rulesUndone = new ArrayList<>();
		/// The side the message was taken to be on: `caller` or `callee`.
		public String leg;
		/// `offer`, `answer`, or null when the message carries no SDP.
		public String sdp;
		/// The response a `reject` would send instead of this request, such
		/// as `603 Declined`, or null.
		public String rejected;
		public Map<String, String> variables = new LinkedHashMap<>();
		public List<String> warnings = new ArrayList<>();
		/// What Save would refuse in the configuration ([Crud#problems]).
		public List<String> problems = new ArrayList<>();
		public String output;
		public String error;
	}

	/// Result of a selection dry-run. `ruleSet` is the selected rule set's
	/// id, or null for passthrough; `variables` is the post-enrichment
	/// application-session snapshot (what rule templates would see);
	/// `warnings` is populated by callers that bracket the run with a
	/// capturing logger, same as [PreviewResult#warnings].
	public static class SelectionResult implements Serializable {
		private static final long serialVersionUID = 1L;
		public String ruleSet;
		public Map<String, String> variables = new LinkedHashMap<>();
		public List<String> warnings = new ArrayList<>();
		public String error;
	}
}
