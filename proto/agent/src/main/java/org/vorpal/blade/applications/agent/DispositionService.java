package org.vorpal.blade.applications.agent;

import java.util.Locale;

import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.Events;


/// What an agent's "Update" does: records one disposition of one call. An
/// outcome (legitimate, suspected scam, confirmed scam, robocall, wrong number,
/// harassment), whether the caller's identity was verified, an action, and free
/// notes. A scam report is one disposition among the others, not a separate
/// verb, so every call gets the same record and the next pop for this number
/// can show what the last agent concluded.
///
/// One disposition fans out three ways, each best effort and independent:
///
///  1. **Block list**, only when the action asks for it: a `spam_numbers` row
///     proxy-block reads, with a treatment from the outcome and an expiry, so
///     the next call from this number is handled at the edge.
///  2. **Catalog label**, when the call has a recorded conversation: a
///     `BLADE_LABEL` row, the catalog's label store and the ground truth a fraud
///     score is calibrated against.
///  3. **The event**: one [BladeEventTypes#CALL_DISPOSITIONED] carrying every
///     field, correlated to the call, so the sink stores it beside the call and
///     [Catalog#history] reads it back for the next pop.
///
/// Outcome maps to a treatment the same way proxy-block's sample does:
/// harassment goes to a review voicemail, robocall to a tarpit, scam is declined.
public final class DispositionService {


	/// The name the analytics sink files [BladeEventTypes#CALL_DISPOSITIONED]
	/// under, which [Catalog] selects on.
	public static final String EVENT = "agentDisposition";

	/// The one action that writes the block list.
	public static final String ACTION_BLOCK = "block";

	private final Catalog catalog;
	private final int expiryDays;
	public DispositionService(Catalog catalog, int expiryDays) {
		this.catalog = catalog;
		this.expiryDays = expiryDays;
	}

	/// The treatment an outcome maps to (see proxy-block's block-list treatments).
	public static String treatmentFor(String outcome) {
		if (outcome == null) {
			return "tarpit";
		}
		switch (outcome.toLowerCase(Locale.ROOT)) {
		case "harassment":
		case "abuse":
			return "review";
		case "robocall":
		case "spam":
			return "tarpit";
		case "scam":
		case "fraud":
		case "confirmed-scam":
		case "suspected-scam":
			return "decline";
		default:
			return "tarpit";
		}
	}

	/// One disposition as the page sends it.
	public static final class Disposition {
		public String vorpalId;
		public String ani;
		public String conversation;
		public String outcome;
		public String identity;
		public String action;
		public String notes;
		/// Reason codes, comma-separated: what tipped the agent off. The labeled
		/// data every signal is later thresholded against, so it is kept as
		/// fixed codes, not prose.
		public String reasons;
		/// The one-glyph disposition, shown to the next agent this number reaches
		/// before they pick up: 1 happy, 2 bemused, 3 meh, 4 irritated, 5 angry
		/// (the doctor's-office pain scale), 6 robot (a synthetic voice), 7 hacker
		/// (a human scammer).
		public Integer face;
	}

	/// Record a disposition. Returns a small result the endpoint echoes to the
	/// console.
	public Result record(Disposition d, String agent) {
		String outcome = (d.outcome == null || d.outcome.isBlank()) ? "legitimate" : d.outcome.trim();
		String treatment = treatmentFor(outcome);
		boolean blocked = ACTION_BLOCK.equals(d.action) && d.ani != null
				&& catalog.blockNumber(d.ani, treatment, "agent:" + outcome, agent, expiryDays);
		boolean labelled = d.conversation != null && catalog.label(d.conversation, outcome, 1.0, "agent:" + agent);
		boolean published = publish(d, outcome, treatment, blocked, agent);
		return new Result(treatment, blocked, labelled, published);
	}

	private boolean publish(Disposition d, String outcome, String treatment, boolean blocked, String agent) {
		// This node's record of the call gives the correlator and its birth
		// instant. A call another node popped is known here only by its hex id;
		// published without a birth instant, the sink files it under that id's
		// open session.
		AgentConsoleRegistry.CallRef call = AgentConsoleRegistry.callRef(d.vorpalId);
		Long vorpalId = (call != null) ? call.vorpalId : parseHex(d.vorpalId);
		java.util.Date startedAt = (call != null) ? call.startedAt : null;
		return Events.publish(vorpalId, startedAt, BladeEventTypes.CALL_DISPOSITIONED, data -> data
				.put("outcome", outcome)
				.put("treatment", treatment)
				.put("blocked", blocked)
				.put("agent", (agent == null) ? "?" : agent)
				.put("identity", blank(d.identity))
				.put("action", blank(d.action))
				.put("notes", blank(d.notes))
				.put("reasons", blank(d.reasons))
				.put("face", d.face)
				.put("ani", blank(d.ani)));
	}

	private static String blank(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	private static Long parseHex(String vorpalIdHex) {
		try {
			return (vorpalIdHex == null) ? null : Long.valueOf(Long.parseLong(vorpalIdHex, 16));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/// What happened, for the console's one-line confirmation.
	public static final class Result {
		public final String treatment;
		public final boolean blocked;
		public final boolean labelled;
		public final boolean published;

		Result(String treatment, boolean blocked, boolean labelled, boolean published) {
			this.treatment = treatment;
			this.blocked = blocked;
			this.labelled = labelled;
			this.published = published;
		}
	}
}
