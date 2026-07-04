package org.vorpal.blade.framework.v3;

import java.io.Serializable;

/// One captured step of a v3 callflow's execution: a SIP message the callflow
/// sent (direction `out`) or observed arriving (direction `in`), tagged — for
/// outbound steps — with the EXACT source location that emitted it: the concrete
/// callflow class, the method (or lambda) that ran, and the line number of the
/// `sendRequest` / `sendResponse` call. Inbound steps carry the callflow class
/// that handled the message but no line pin (arrival isn't a line of app code).
///
/// Each step also carries the raw SIP message text ([#getMessage] — start line,
/// headers, body as the container renders it), the call's `X-Vorpal-Session` id,
/// and a timestamp. Those are what let steps captured by DIFFERENT apps in the
/// same routed chain be stitched back into one end-to-end timeline (the id is
/// stable across the whole app chain) and ordered — so a trace can answer "which
/// app in the string misbehaved," down to the line and the message on the wire,
/// which neither the v2 sequence-diagram log (class name only) nor call-level
/// analytics can.
///
/// Serializable so it rides the `SipApplicationSession` through cluster failover
/// like the rest of a callflow's state.
public final class CallStep implements Serializable {
	private static final long serialVersionUID = 2L;

	public static final String OUT = "out";
	public static final String IN = "in";

	private final String sessionId;  // X-Vorpal-Session — stable across the app chain
	private final long epochMillis;  // when this step fired
	private final int order;         // per-app sequence within this callflow
	private final String direction;  // "out" (we sent it) | "in" (we observed it arrive)
	private final String kind;       // "request" | "response"
	private final String label;      // SIP method ("INVITE") or status code ("200")
	private final String className;  // concrete callflow class (which app code)
	private final String methodName; // method or lambda that called send* ("process"/"received" for in)
	private final int line;          // source line of the send* call (-1 for in)
	private final String message;    // raw SIP message text, or null if unavailable

	public CallStep(String sessionId, long epochMillis, int order, String direction, String kind,
			String label, String className, String methodName, int line, String message) {
		this.sessionId = sessionId;
		this.epochMillis = epochMillis;
		this.order = order;
		this.direction = direction;
		this.kind = kind;
		this.label = label;
		this.className = className;
		this.methodName = methodName;
		this.line = line;
		this.message = message;
	}

	public String getSessionId() { return sessionId; }

	public long getEpochMillis() { return epochMillis; }

	public int getOrder() { return order; }

	public String getDirection() { return direction; }

	public String getKind() { return kind; }

	public String getLabel() { return label; }

	public String getClassName() { return className; }

	public String getMethodName() { return methodName; }

	public int getLine() { return line; }

	public String getMessage() { return message; }

	/// Short, unqualified callflow class name — the human-facing "which app/flow".
	public String getSimpleClassName() {
		int dot = className.lastIndexOf('.');
		return dot >= 0 ? className.substring(dot + 1) : className;
	}

	@Override
	public String toString() {
		return "#" + order + " " + direction + " " + kind + " " + label
				+ " @ " + getSimpleClassName() + "." + methodName + ":" + line;
	}
}
