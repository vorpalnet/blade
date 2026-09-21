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

	/// One prior call: when, how long, who was dialed, and its conversation id.
	public static final class Call {
		public final String whenUtc;
		public final long durationMillis;
		public final String dialed;
		public final String conversation;

		public Call(String whenUtc, long durationMillis, String dialed, String conversation) {
			this.whenUtc = whenUtc;
			this.durationMillis = durationMillis;
			this.dialed = dialed;
			this.conversation = conversation;
		}
	}
}
