package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;

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
	///                           values that an upstream Attribute Selector
	///                           would have written, or to override an
	///                           environment variable for the run. May be null.
	public static PreviewResult preview(CrudConfiguration config, String ruleSetId,
			String messageText, String lifecycleEvent, Map<String, String> initialVariables) {
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

		// Pre-populate the session with operator-supplied variables — this
		// stands in for the Attribute Selectors / routing layer that would
		// have run before this rule set in production, and lets operators
		// override env vars for the dry run.
		if (initialVariables != null && !initialVariables.isEmpty()) {
			SipApplicationSession appSession = msg.getApplicationSession();
			if (appSession != null) {
				for (Map.Entry<String, String> e : initialVariables.entrySet()) {
					if (e.getKey() != null && e.getValue() != null) {
						appSession.setAttribute(e.getKey(), e.getValue());
					}
				}
			}
		}

		// Apply the rule set, recording which rules' filters matched.
		// applyRules logs but doesn't return matches, so we re-check
		// matches() ourselves to populate rulesFired.
		for (Rule rule : ruleSet.getRules()) {
			if (rule.matches(msg, lifecycleEvent)) {
				result.rulesFired.add(rule.getId());
				rule.process(msg);
			}
		}

		result.output = SipMessageSerializer.serialize(msg);
		result.variables = snapshotVariables(msg.getApplicationSession());
		return result;
	}

	/// Snapshot the string-typed attributes on the application session at
	/// end of a preview pass. These are the variables produced by `read`-
	/// flavoured operations and consumed by `${var}` substitution in
	/// downstream `create` / `update` ops; surfacing them in the UI shows
	/// operators what their reads actually captured.
	private static Map<String, String> snapshotVariables(SipApplicationSession appSession) {
		Map<String, String> out = new LinkedHashMap<>();
		if (appSession == null) return out;
		for (String name : appSession.getAttributeNameSet()) {
			Object v = appSession.getAttribute(name);
			if (v instanceof String) out.put(name, (String) v);
		}
		return out;
	}

	/// Result of a dry-run preview. Either `error` is set (parse / config
	/// error) or `output` / `rulesFired` / `variables` describe the
	/// transformed message and the session state it leaves behind. The
	/// `warnings` list is populated by callers that bracket the preview
	/// with a logger that captures per-request log events — the
	/// AdminServer-side preview servlet does this so internal operation
	/// errors (which the operations themselves swallow) surface in the UI.
	public static class PreviewResult implements Serializable {
		private static final long serialVersionUID = 1L;
		public String lifecycleEvent;
		public List<String> rulesFired = new ArrayList<>();
		public Map<String, String> variables = new LinkedHashMap<>();
		public List<String> warnings = new ArrayList<>();
		public String output;
		public String error;
	}
}
