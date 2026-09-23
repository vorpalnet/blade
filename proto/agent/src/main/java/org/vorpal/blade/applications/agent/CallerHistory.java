package org.vorpal.blade.applications.agent;

import java.util.ArrayList;
import java.util.List;

/// What the call catalog knows about a caller, for the screen-pop: how often
/// they have called, their recent calls, and any labels a reviewer or an earlier
/// agent report already attached (so "reported for scam twice before" shows up
/// the moment the phone rings).
///
/// Every field is best-effort. With no data source, or a caller never seen
/// before, this is empty and the pop simply shows what the INVITE carried.
public final class CallerHistory {

	public static final CallerHistory EMPTY = new CallerHistory(0, new ArrayList<>(), new ArrayList<>(),
			new ArrayList<>());

	public final int callCount;
	public final List<Call> recent;
	/// The face the last agent recorded for this caller, 1 to 7, or null:
	/// shown before the number, before the agent picks up.
	public Integer lastFace;
	public final List<String> priorLabels;
	/// Topics of this caller's earlier conversations, newest first (catalog
	/// `BLADE_CONVERSATION_ATTR` name=topic). Empty until a summarizer writes them.
	public final List<String> topics;

	public CallerHistory(int callCount, List<Call> recent, List<String> priorLabels, List<String> topics) {
		this.callCount = callCount;
		this.recent = recent;
		this.priorLabels = priorLabels;
		this.topics = topics;
	}

	/// One prior call: when, how long (-1 when its end was never recorded), who
	/// was dialed, its conversation id, and
	/// what the agent who took it concluded (the latest `agentDisposition` event
	/// on that call's session), each null when unknown.
	public static final class Call {
		public final String whenUtc;
		public final long durationMillis;
		public final String dialed;
		public final String conversation;
		public String outcome;
		public String identity;
		public String action;
		public String notes;
		public String agent;
		/// Reason codes the agent ticked, comma-separated.
		public String reasons;
		/// The face that agent recorded, 1 to 7, or null.
		public Integer face;
		/// The post-call review's labels and evidence line, when one was filed.
		public String reviewLabels;
		public String reviewText;
		/// The caller's first few lines on that call, from the stored transcript,
		/// so the next agent sees what this number said last time.
		public java.util.List<String> said = new ArrayList<>();

		public Call(String whenUtc, long durationMillis, String dialed, String conversation) {
			this.whenUtc = whenUtc;
			this.durationMillis = durationMillis;
			this.dialed = dialed;
			this.conversation = conversation;
		}
	}
}
