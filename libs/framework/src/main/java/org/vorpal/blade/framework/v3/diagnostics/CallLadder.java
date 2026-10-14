package org.vorpal.blade.framework.v3.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// One call's SIP messages as this application saw them, reduced to what a
/// ladder diagram needs, and the verdict they support: which side let the call
/// down ([#verdict]).
///
/// **Small on purpose.** A step is the time, the direction, the leg, the method
/// or status, the CSeq number and the remote address: no headers, no body, no
/// phone number. The first [#HEAD] steps and the last [#TAIL] are kept; a call
/// with more (a long call refreshing its session) records how many were left
/// out between them.
///
/// **The verdict is kept as the steps arrive,** not read back from them, so a
/// step left out of the middle cannot hide a request that was never answered.
/// Not thread-safe; [FlightRecorder] holds the lock.
public final class CallLadder {

	static final int HEAD = 16;
	static final int TAIL = 48;

	/// One message.
	public static final class Step {
		public final long at;
		public final boolean out;
		public final int leg;
		public final String method;
		/// The status of a response; 0 for a request.
		public final int status;
		public final long cseq;
		/// The address at the far end: where a request was sent (its
		/// Request-URI's host and port) or the address a message came from.
		/// Null for a response the container made itself because nothing
		/// answered.
		public final String peer;

		public Step(long at, boolean out, int leg, String method, int status, long cseq, String peer) {
			this.at = at;
			this.out = out;
			this.leg = leg;
			this.method = method;
			this.status = status;
			this.cseq = cseq;
			this.peer = peer;
		}
	}

	/// What let the call down. `fault` is `downstream` (a server the call was
	/// sent to) or `application` (this application: a framework or application
	/// bug). `finding` is one of:
	/// - `silent`: a request this application sent never got a final response;
	/// - `timeout`: the container answered a request itself because the server
	///   never did (a final response with no remote address);
	/// - `forgot`: the server answered 481, so it no longer knew the dialog;
	/// - `error`: the server answered 5xx;
	/// - `credentials`: the server answered 401, 403 or 407;
	/// - `unanswered`: a request this application received never got a final
	///   response from it;
	/// - `unrelayed`: a BYE this application received was never sent on to the
	///   other leg.
	public static final class Verdict {
		public final String fault;
		public final String finding;
		/// The message the finding is about: the request that went unanswered,
		/// or the response that refused it.
		public final Step step;
		/// The server the finding names: where the unanswered or refused request
		/// was sent, or where an unanswered request came from.
		public final String peer;

		Verdict(String fault, String finding, Step step, String peer) {
			this.fault = fault;
			this.finding = finding;
			this.step = step;
			this.peer = peer;
		}
	}

	private final List<Step> head = new ArrayList<>();
	private final ArrayDeque<Step> tail = new ArrayDeque<>();
	private int omitted;

	private final Map<String, Integer> legs = new HashMap<>();

	/// Requests waiting for a final response, by leg, CSeq, method and
	/// direction, in the order they were seen.
	private final Map<String, Step> open = new LinkedHashMap<>();
	private final Set<Integer> cancelledIn = new HashSet<>();
	private final Set<Integer> cancelledOut = new HashSet<>();
	private final Set<Integer> byeIn = new HashSet<>();
	private final Set<Integer> byeOut = new HashSet<>();
	private final Set<Integer> answeredLegs = new HashSet<>();
	private Step firstByeIn;
	private boolean answered;
	/// A final response to an INVITE this application sent has arrived.
	private boolean inviteSettled;
	/// The first refusal of a request this application sent that is a
	/// fault, with its finding; INVITE refusals are judged at the end, since a
	/// refused leg the call recovered from is no fault.
	private Verdict refusal;
	private Verdict inviteRefusal;

	Long vorpalId;
	java.util.Date startedAt;
	org.vorpal.blade.framework.v3.events.CallScope scope;

	/// The leg number for a SIP session id: 1 for the first seen, then 2...
	public int leg(String sessionId) {
		Integer n = legs.get(sessionId);
		if (n == null) {
			n = legs.size() + 1;
			legs.put(sessionId, n);
		}
		return n;
	}

	public void add(Step s) {
		if (head.size() < HEAD) {
			head.add(s);
		} else {
			tail.addLast(s);
			if (tail.size() > TAIL) {
				tail.removeFirst();
				omitted++;
			}
		}
		if (s.status == 0) {
			request(s);
		} else if (s.status >= 200) {
			response(s);
		}
	}

	private static String key(int leg, long cseq, String method, boolean out) {
		return leg + "|" + cseq + "|" + method + "|" + (out ? "out" : "in");
	}

	private void request(Step s) {
		switch (s.method) {
		case "ACK":
			return;
		case "CANCEL":
			// The container answers a CANCEL, and the INVITE it cancels with 487,
			// itself: neither answer passes through the application. But a caller
			// who cancels after the callee had already answered or refused was
			// waiting for an answer that never reached them: the INVITE stays
			// unanswered, unless another INVITE sent is still ringing.
			if (s.out) {
				cancelledOut.add(s.leg);
			} else {
				cancelledIn.add(s.leg);
				if (!inviteSettled || ringing()) {
					open.remove(key(s.leg, s.cseq, "INVITE", false));
				}
			}
			return;
		case "BYE":
			(s.out ? byeOut : byeIn).add(s.leg);
			if (!s.out && firstByeIn == null) {
				firstByeIn = s;
			}
			break;
		default:
			break;
		}
		open.put(key(s.leg, s.cseq, s.method, s.out), s);
	}

	/// An INVITE this application sent still has no final response.
	private boolean ringing() {
		for (Step o : open.values()) {
			if (o.out && "INVITE".equals(o.method)) {
				return true;
			}
		}
		return false;
	}

	private void response(Step s) {
		// A response sent answers a request received, and the other way round.
		Step asked = open.remove(key(s.leg, s.cseq, s.method, !s.out));
		if (!s.out && "INVITE".equals(s.method)) {
			inviteSettled = true;
		}
		if ("INVITE".equals(s.method) && s.status < 300) {
			answered = true;
			answeredLegs.add(s.leg);
		}
		if (s.out || s.status < 400) {
			return;
		}
		String finding = refusalFinding(s);
		if (finding == null) {
			return;
		}
		Verdict v = new Verdict("downstream", finding, s, (asked != null) ? asked.peer : s.peer);
		if ("INVITE".equals(s.method)) {
			if (inviteRefusal == null) {
				inviteRefusal = v;
			}
		} else if (refusal == null) {
			refusal = v;
		}
	}

	/// What a refusal of a request this application sent says about the
	/// server, or null when it is the callee declining, or the 487 its own
	/// CANCEL caused.
	private String refusalFinding(Step s) {
		if (s.status == 487 && cancelledOut.contains(s.leg)) {
			return null;
		}
		if (s.peer == null) {
			return "timeout";
		}
		if (s.status == 481) {
			return "forgot";
		}
		if (s.status >= 500 && s.status < 600) {
			return "error";
		}
		if (s.status == 401 || s.status == 403 || s.status == 407) {
			return "credentials";
		}
		return null;
	}

	/// What let the call down, or null when nothing did: the server's fault
	/// first, since an application waiting on a server that never answers
	/// cannot answer its own caller either.
	///
	/// 1. A request sent that never got a final response (`silent`).
	/// 2. A refusal that is a fault: of an in-dialog request always; of an
	///    INVITE only when the call was never answered.
	/// 3. A request received that this application never answered
	///    (`unanswered`).
	/// 4. A BYE received while another answered leg never saw one, sent or
	///    received (`unrelayed`).
	public Verdict verdict() {
		for (Step s : open.values()) {
			if (s.out) {
				return new Verdict("downstream", "silent", s, s.peer);
			}
		}
		if (refusal != null) {
			return refusal;
		}
		if (inviteRefusal != null && !answered) {
			return inviteRefusal;
		}
		for (Step s : open.values()) {
			if (!s.out) {
				return new Verdict("application", "unanswered", s, s.peer);
			}
		}
		if (firstByeIn != null) {
			for (Integer leg : answeredLegs) {
				if (!byeIn.contains(leg) && !byeOut.contains(leg)) {
					return new Verdict("application", "unrelayed", firstByeIn, firstByeIn.peer);
				}
			}
		}
		return null;
	}

	/// The steps kept, oldest first.
	public List<Step> steps() {
		List<Step> all = new ArrayList<>(head);
		all.addAll(tail);
		return all;
	}

	/// How many steps were left out between the first [#HEAD] and the last
	/// [#TAIL].
	public int omitted() {
		return omitted;
	}
}
