package org.vorpal.blade.framework.v3.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;

import org.vorpal.blade.framework.v2.analytics.Analytics;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.CallScope;
import org.vorpal.blade.framework.v3.events.Events;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// Every call's SIP messages, kept small and in memory, and written out only
/// for a call that went wrong ([BladeEventTypes#CALL_TRACED]).
///
/// **Why every call.** A complaint about a call that disappeared arrives days
/// later. The Trace app records only calls a rule was armed for beforehand, and
/// the logs hold one line per message spread over a file per application per
/// server. This keeps a [CallLadder] for every call from its first INVITE,
/// costs memory only while the call is up, and is dropped when the call ends
/// well. A call that ends badly carries its own evidence, with the verdict
/// worked out: which server, or this application, let it down.
///
/// **Where it hooks.** Every message the framework sends or receives is drawn
/// on the log's sequence diagram, so [#record] is called from there, whatever
/// the callflow and whatever the log level. [#finish] runs when the container
/// is about to invalidate the application session, or destroys it, and from
/// [org.vorpal.blade.framework.v3.net.DroppedCalls] when a call expires
/// dropped.
///
/// **Node-local.** The ladders are in this application's memory on this
/// server, never in the replicated session: a call that fails over to another
/// server starts a new ladder there. At most [#MAX_CALLS] are held; past that
/// the oldest is let go, so a session the container never reports ending
/// cannot grow it without bound.
public final class FlightRecorder {

	static final int MAX_CALLS = 20_000;

	private static final Map<String, CallLadder> CALLS = Collections
			.synchronizedMap(new LinkedHashMap<String, CallLadder>(1024, 0.75f, false) {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<String, CallLadder> eldest) {
					return size() > MAX_CALLS;
				}
			});

	private FlightRecorder() {
	}

	/// Records one message sent (`out`) or received. A call's ladder starts at
	/// its initial INVITE, either way; a message of a session with no ladder
	/// (a REGISTER, an OPTIONS ping, a call already finished) is ignored.
	/// Never throws: this runs on the container's thread for every message.
	public static void record(boolean out, SipServletRequest request, SipServletResponse response) {
		try {
			SipServletMessage message = (request != null) ? request : response;
			if (message == null) {
				return;
			}
			SipApplicationSession app = message.getApplicationSession();
			String id = app.getId();
			CallLadder ladder = CALLS.get(id);
			if (ladder == null) {
				if (request == null || !request.isInitial() || !"INVITE".equals(request.getMethod())) {
					return;
				}
				ladder = new CallLadder();
				CALLS.put(id, ladder);
			}
			synchronized (ladder) {
				if (ladder.vorpalId == null) {
					ladder.vorpalId = Analytics.getVorpalId(app);
					if (ladder.vorpalId != null) {
						ladder.startedAt = Analytics.getCallStartedAt(app);
						ladder.scope = CallScope.of(app);
					}
				}
				ladder.add(new CallLadder.Step(System.currentTimeMillis(), out, ladder.leg(message.getSession().getId()),
						message.getMethod(), (response != null) ? response.getStatus() : 0, cseq(message),
						peer(out, request, response)));
			}
		} catch (Throwable t) {
			// A missing step is better than a failed message.
		}
	}

	/// The call has ended: its ladder is let go, and published first when the
	/// call went wrong or `dropped` says it was. Never throws.
	public static void finish(SipApplicationSession app, boolean dropped) {
		try {
			if (app == null) {
				return;
			}
			CallLadder ladder = CALLS.remove(app.getId());
			if (ladder == null) {
				return;
			}
			synchronized (ladder) {
				CallLadder.Verdict verdict = ladder.verdict();
				if (verdict == null && !dropped) {
					return;
				}
				publish(ladder, verdict, dropped);
			}
		} catch (Throwable t) {
			// A lost ladder must never fail the container's cleanup.
		}
	}

	/// How many calls are being recorded now.
	public static int size() {
		return CALLS.size();
	}

	static void clear() {
		CALLS.clear();
	}

	private static void publish(CallLadder ladder, CallLadder.Verdict verdict, boolean dropped) {
		Events.publish(ladder.vorpalId, ladder.startedAt, ladder.scope, BladeEventTypes.CALL_TRACED, data -> {
			if (verdict != null) {
				data.put("fault", verdict.fault);
				data.put("finding", verdict.finding);
				if (verdict.step != null) {
					data.put("method", verdict.step.method);
					if (verdict.step.status != 0) {
						data.put("status", verdict.step.status);
					}
					data.put("leg", verdict.step.leg);
				}
				data.put("peer", verdict.peer);
			}
			data.put("dropped", dropped);
			data.put("omitted", ladder.omitted());
			ArrayNode steps = data.putArray("steps");
			for (CallLadder.Step s : ladder.steps()) {
				ObjectNode o = steps.addObject();
				o.put("t", s.at);
				o.put("dir", s.out ? "out" : "in");
				o.put("leg", s.leg);
				o.put("method", s.method);
				if (s.status != 0) {
					o.put("status", s.status);
				}
				o.put("cseq", s.cseq);
				if (s.peer != null) {
					o.put("peer", s.peer);
				}
			}
		});
	}

	private static long cseq(SipServletMessage message) {
		String h = message.getHeader("CSeq");
		if (h == null) {
			return 0;
		}
		try {
			return Long.parseLong(h.trim().split("\\s+", 2)[0]);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/// The far end: the address a message came from; for a request sent, the
	/// host and port it was sent to (never the user part, which is a phone
	/// number); for a response sent, the address its request came from.
	private static String peer(boolean out, SipServletRequest request, SipServletResponse response) {
		if (!out) {
			SipServletMessage m = (request != null) ? request : response;
			return address(m.getRemoteAddr(), m.getRemotePort());
		}
		if (request != null) {
			URI uri = request.getRequestURI();
			if (uri instanceof SipURI) {
				SipURI sip = (SipURI) uri;
				return sip.getPort() > 0 ? sip.getHost() + ":" + sip.getPort() : sip.getHost();
			}
			return null;
		}
		SipServletRequest answered = response.getRequest();
		return (answered == null) ? null : address(answered.getRemoteAddr(), answered.getRemotePort());
	}

	private static String address(String addr, int port) {
		if (addr == null) {
			return null;
		}
		return port > 0 ? addr + ":" + port : addr;
	}
}
