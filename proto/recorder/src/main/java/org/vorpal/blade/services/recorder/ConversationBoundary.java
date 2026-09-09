package org.vorpal.blade.services.recorder;

import java.util.List;

import org.vorpal.blade.framework.v3.media.MediaDirection;

/// Decides what an INVITE means for a recording, from nothing but the INVITE.
///
/// ## Why this is the whole mechanism
///
/// A call is one conversation until the party on the far side changes, and that
/// is an event this application already sees: the App Router routes **initial**
/// requests through the chain, so a call that moves to a new party arrives here
/// as a new initial INVITE when the recorder sits downstream of the application
/// that transferred it.
///
/// With the recorder upstream, nearer the trunk, a transfer completed by the
/// application behind it arrives as a re-INVITE on the far leg carrying the
/// target's SDP. The same dialog, a different party. [AnchoredSdp#newParty]
/// tells that apart from a party moving its own media by the offer's origin
/// line: RFC 3264 keeps a party's `o=` username and session id constant and
/// moves only the version, so a changed origin identity is a changed party.
/// `RecorderServlet.requestEvent` closes the conversation and opens the next
/// on a fresh leg when it sees one. Either way this application needs to know
/// nothing about how a transfer was performed, which SIP method carried it, or
/// which application performed it. Transfer is somebody else's app; routing is
/// the App Router's job; this one watches what passes through.
///
/// ## Why it is a class and not four lines in the servlet
///
/// A servlet cannot be instantiated outside the container, so logic that lives
/// on one cannot be tested. Everything decidable from the message is decided
/// here, against plain values, and the servlet is left with the part that
/// genuinely needs a container.
///
/// ## Hold
///
/// Hold is not a boundary. It is an absence of content inside one conversation,
/// so it pauses the recorder and resumes into the same recording, exactly as a
/// PCI pause over a card number does.
///
/// RFC 3264 hold is an offer in which the offerer stops receiving: `sendonly`,
/// where they send hold music and take nothing back, or `inactive`, where
/// neither direction runs. `recvonly` is deliberately **not** treated as hold.
/// The far party is still receiving, so the conversation may still be audible,
/// and a compliance recording should err toward capturing rather than toward a
/// silent gap. Directions are read from the perspective of the party that sent
/// the SDP, which is where this kind of code usually goes wrong; see
/// [MediaDirection#reverse].
public final class ConversationBoundary {

	private ConversationBoundary() {
	}

	/// What to do about this INVITE.
	///
	/// @param initial    whether the container reports it as an initial request,
	///                   `SipServletRequest.isInitial()`
	/// @param offered    the effective direction of each m-line in the offer, as
	///                   [org.vorpal.blade.framework.v3.media.SdpMedia#captureDirections]
	///                   returns them. Null or empty for an INVITE carrying no
	///                   offer, which is a session refresh and means nothing here.
	/// @param paused     whether the recording is currently paused
	/// @return the action to take, never null
	public static RecordingAction decide(boolean initial, List<MediaDirection> offered, boolean paused) {
		if (initial) {
			// A new party, therefore a new conversation, therefore a new
			// recording. Whether one was already running is the caller's problem
			// to tidy up: it has to be stopped and released either way.
			return RecordingAction.START_CONVERSATION;
		}
		if (offered == null || offered.isEmpty()) {
			// An offerless re-INVITE is a session refresh. It says nothing about
			// what the parties are doing.
			return RecordingAction.NONE;
		}
		boolean held = isHold(offered);
		if (held && !paused) {
			return RecordingAction.PAUSE;
		}
		if (!held && paused) {
			return RecordingAction.RESUME;
		}
		return RecordingAction.NONE;
	}

	/// Whether an offer puts the call on hold: every m-line stopped receiving.
	///
	/// Every m-line rather than any, so a call that still has one live stream is
	/// still recorded. A partial hold is not a hold, and treating it as one would
	/// drop audio that was flowing.
	public static boolean isHold(List<MediaDirection> offered) {
		if (offered == null || offered.isEmpty()) {
			return false;
		}
		for (MediaDirection direction : offered) {
			if (direction != MediaDirection.SENDONLY && direction != MediaDirection.INACTIVE) {
				return false;
			}
		}
		return true;
	}
}
