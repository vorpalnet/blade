package org.vorpal.blade.framework.v3.net;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipSession;
import javax.servlet.sip.SipSession.State;
import javax.servlet.sip.UAMode;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/// A call whose signaling was dropped, found when the container gives up on it
/// ([BladeEventTypes#CALL_DROPPED]).
///
/// **Why expiry is the moment.** A call that ends the ordinary way, a BYE on an
/// answered call or a CANCEL or refusal before one, leaves both legs
/// terminated, and the container invalidates the application session as soon
/// as it is ready to. A call whose messages a bug stopped relaying never gets
/// there: one leg waits for a response, or stays up after the other hung up,
/// until the application session expires. So expiry needs no timer of its own,
/// and what the legs look like then says what went missing.
///
/// **Which calls were dropped.** Every pair of linked legs (a B2BUA's caller and
/// callee) is read as it stands at expiry, before the last-chance keep-alive
/// probe can change it:
/// - both up: a live call, which the probe keeps or hangs up; not dropped;
/// - both ended: a finished call the application never invalidated; not dropped;
/// - anything else was dropped: a leg still setting up (an INVITE that never got
///   its final response) or one leg up after the other ended (a BYE never
///   relayed). A leg whose partner is gone counts as a partner that ended.
///
/// A proxy has no linked legs, so it never reports one.
public final class DroppedCalls {

	/// The application-session attribute marking a call already reported, so a
	/// session the probe extends and that expires again is reported once.
	static final String REPORTED = "blade.analytics.dropped";

	private DroppedCalls() {
	}

	/// Reads the legs of an expiring application session and publishes
	/// [BladeEventTypes#CALL_DROPPED] when they show a dropped call. Never throws:
	/// this runs inside the container's expiry callback.
	public static void expiring(SipApplicationSession appSession) {
		try {
			if (appSession == null || !appSession.isValid() || appSession.getAttribute(REPORTED) != null) {
				return;
			}
			List<SipSession> legs = linkedLegs(appSession);
			if (legs.isEmpty() || !dropped(legs)) {
				return;
			}
			appSession.setAttribute(REPORTED, Boolean.TRUE);
			long ageSeconds = (System.currentTimeMillis() - appSession.getCreationTime()) / 1000;
			Events.publish(appSession, BladeEventTypes.CALL_DROPPED, data -> {
				data.put("ageSeconds", ageSeconds);
				ArrayNode array = data.putArray("legs");
				for (SipSession leg : legs) {
					ObjectNode o = array.addObject();
					o.put("role", role(leg, legs));
					o.put("state", state(leg));
					o.put("inviteOpen", inviteOpen(leg));
				}
			});
		} catch (RuntimeException e) {
			// A missing fact is better than a failed expiry.
		}
	}

	/// Dropped unless every leg is up, or every leg has ended.
	static boolean dropped(List<SipSession> legs) {
		boolean allUp = true, allEnded = true;
		for (SipSession leg : legs) {
			String s = state(leg);
			allUp &= "CONFIRMED".equals(s);
			allEnded &= "TERMINATED".equals(s) || "GONE".equals(s);
		}
		return !allUp && !allEnded;
	}

	/// The valid legs that are half of a linked pair, plus a placeholder for a
	/// partner that is no longer there. A placeholder is `null` in the list.
	private static List<SipSession> linkedLegs(SipApplicationSession appSession) {
		List<SipSession> legs = new ArrayList<>();
		@SuppressWarnings("unchecked")
		Iterator<SipSession> it = (Iterator<SipSession>) appSession.getSessions("SIP");
		while (it.hasNext()) {
			SipSession leg = it.next();
			if (leg == null || !leg.isValid() || leg.getAttribute(Callflow.LINKED_SESSION) == null) {
				continue;
			}
			legs.add(leg);
			SipSession partner = Callflow.getLinkedSession(leg);
			if (partner == null || !partner.isValid()) {
				legs.add(null);
			}
		}
		return legs;
	}

	/// The leg's dialog state, or `GONE` for a partner no longer there.
	static String state(SipSession leg) {
		if (leg == null || !leg.isValid()) {
			return "GONE";
		}
		State s = leg.getState();
		return s == null ? "UNKNOWN" : s.name();
	}

	/// `caller` or `callee`: as the framework labelled it on answer, else by
	/// which side of an open INVITE the leg is on, else by which was created
	/// first. `gone` for a missing partner.
	static String role(SipSession leg, List<SipSession> legs) {
		if (leg == null || !leg.isValid()) {
			return "gone";
		}
		Object labelled = leg.getAttribute("userAgent");
		if (labelled instanceof String) {
			return (String) labelled;
		}
		if (leg.getActiveInvite(UAMode.UAS) != null) {
			return "caller";
		}
		if (leg.getActiveInvite(UAMode.UAC) != null) {
			return "callee";
		}
		for (SipSession other : legs) {
			if (other != null && other != leg && other.isValid()) {
				return leg.getCreationTime() <= other.getCreationTime() ? "caller" : "callee";
			}
		}
		return "leg";
	}

	/// True while an INVITE on this leg has no final response: the caller still
	/// waiting for one, or the callee never having sent one.
	static boolean inviteOpen(SipSession leg) {
		return leg != null && leg.isValid()
				&& (leg.getActiveInvite(UAMode.UAS) != null || leg.getActiveInvite(UAMode.UAC) != null);
	}
}
