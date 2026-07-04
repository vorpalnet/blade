package org.vorpal.blade.framework.v3.diagnostics;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.vorpal.blade.framework.v3.CallStep;

/// One logical call's END-TO-END trace: every [CallStep] from every app in the
/// routed chain that shares this call's `X-Vorpal-Session` id, ordered into a
/// single timeline.
///
/// This is the artifact that answers "which app in the string misbehaved." A
/// single call is really N per-app `SipApplicationSession`s stitched by the
/// container; each app's v3 callflow records its own steps tagged with the
/// stable session id, and [CallTraceAggregator] merges them here. Each step
/// already carries its concrete callflow class ([CallStep#getSimpleClassName]),
/// so the app that emitted any given message — the 500, the BYE, whatever — is
/// right there in the timeline.
public final class CallTrace implements Serializable {
	private static final long serialVersionUID = 1L;

	private final String sessionId;
	private final List<CallStep> steps;

	public CallTrace(String sessionId, List<CallStep> steps) {
		this.sessionId = sessionId;
		this.steps = steps;
	}

	public String getSessionId() {
		return sessionId;
	}

	/// All steps of the call, ordered across the whole chain (by timestamp, then
	/// per-app order).
	public List<CallStep> getSteps() {
		return steps;
	}

	/// The distinct apps (callflow classes) this call passed through, in the
	/// order they first acted — the chain as it actually executed. A gap between
	/// this and the FSMAR-planned chain is itself diagnostic (an app that was
	/// routed to but never emitted anything is a prime suspect).
	public List<String> appsInOrder() {
		List<String> apps = new ArrayList<>();
		for (CallStep s : steps) {
			String app = s.getSimpleClassName();
			if (!apps.contains(app)) {
				apps.add(app);
			}
		}
		return apps;
	}

	/// Elapsed wall-clock of the whole call, first step to last, in millis
	/// (0 if fewer than two steps). The coarse latency figure; per-hop gaps come
	/// from consecutive step timestamps.
	public long durationMillis() {
		if (steps.size() < 2) {
			return 0L;
		}
		return steps.get(steps.size() - 1).getEpochMillis() - steps.get(0).getEpochMillis();
	}

	public int size() {
		return steps.size();
	}
}
