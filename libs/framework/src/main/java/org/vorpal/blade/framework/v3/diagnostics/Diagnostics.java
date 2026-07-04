package org.vorpal.blade.framework.v3.diagnostics;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.sip.SipServletRequest;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Framework-level tracing policy: the armed [TraceRule]s that decide which live
/// calls get source-line traced, edited in the Configurator and published live.
///
/// This is the "arm a Selector at runtime, catch the calls, remove it" control.
/// It's framework-level (one place arms tracing across every service) rather
/// than per-service, so an operator debugging a chain says "trace calls from
/// this From, anywhere" once. It's OFF by default and does nothing until a rule
/// is added, so it's safe to leave deployed.
///
/// [#armFor] is called once when a call is first seen (the initial request); a
/// match arms that call's `SipApplicationSession` and everything downstream in
/// the chain inherits the decision via the stable `X-Vorpal-Session` id.
public class Diagnostics implements Serializable {
	private static final long serialVersionUID = 1L;

	protected boolean enabled = false;
	protected List<TraceRule> rules = new ArrayList<>();

	@JsonPropertyDescription("Master switch. When false, no call is ever traced regardless of the rules below.")
	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@JsonPropertyDescription("Arming rules — each a Selector (which calls) plus a capture cap. Empty = nothing traced.")
	public List<TraceRule> getRules() {
		return rules;
	}

	public void setRules(List<TraceRule> rules) {
		this.rules = rules;
	}

	/// Decide whether this initial request should be traced, and claim a capture
	/// slot if so. Returns the matching rule (for labelling) or null. Evaluate
	/// this ONCE per call, at the initial request; the caller then calls
	/// `Callflow.enableTrace(appSession)` on a non-null result.
	///
	/// Skips exhausted rules cheaply before touching the request, so a spent
	/// rule adds no per-call `Selector` cost. Runtime-only (uses
	/// `Selector.findKey`, which needs the SIP container); the count/disarm logic
	/// it relies on is unit-tested via [TraceRule#tryConsume].
	public TraceRule armFor(SipServletRequest request) {
		if (!enabled || request == null || rules == null) {
			return null;
		}
		for (TraceRule rule : rules) {
			if (rule == null || rule.getSelector() == null || rule.isExhausted()) {
				continue;
			}
			if (rule.getSelector().findKey(request) != null && rule.tryConsume()) {
				return rule;
			}
		}
		return null;
	}
}
