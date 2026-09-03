package org.vorpal.blade.framework.v3.events;

/// The canonical CloudEvents `type` names BLADE itself emits.
///
/// These exist so the analytics stream stops being discriminated by
/// `instanceof` on a deserialized Java class — the mechanism that ties every
/// consumer to BLADE's classpath and makes selector-based routing impossible.
/// Stamping one of these as [EventPublisher#PROP_TYPE] costs nothing today and
/// is what lets a consumer filter with a JMS selector tomorrow.
///
/// **Start and stop are distinct types on purpose.** On the wire today a
/// `Session` start and a `Session` stop are the *same class*, told apart by
/// whether `destroyed` is null — and `Application` likewise. That forces every
/// consumer to infer intent from a null field. Naming them separately retires
/// the inference.
///
/// **The call and transfer names are types, not payload fields.** They were
/// briefly collapsed into [#CALL_EVENT] with the real name buried in
/// `data.eventName`, on the reasoning that event names are operator-defined at
/// runtime in each app's `analytics.events` configuration. That reasoning is only
/// half right: an operator can invent names, but the framework's own names are a
/// **closed set defined in framework code** — `InitialInvite`, `Terminate`,
/// `BlindTransfer` and `ReferTransfer` publish exactly the eleven below and no
/// configuration changes that. Collapsing them cost the thing this bus is for: a
/// transfer app could not select on `eventType` for refer events, and would have
/// had to receive every call event on the bus and filter in code. So the closed
/// set gets real types, and [#CALL_EVENT] stays as the fallback for names an
/// operator invents — which means nobody's existing configuration breaks.
///
/// **One namespace: `org.vorpal.blade.` — the project's Java package root.**
/// These have been renamed twice, both times before anything shipped. They
/// started under `net.vorpal.blade.analytics.` — from when the only consumer
/// was the analytics database. They are not analytics events; they are facts
/// about a call that analytics happens to record, and a transfer app
/// subscribing to an `...analytics.transfer.requested` name would be reading a
/// name that lies about who it is for — so `analytics` came out. Then the
/// whole prefix moved from `net.` to `org.` to match the package root the rest
/// of BLADE lives under, rather than carrying a second reverse-DNS identity
/// nothing else uses. Both renames were free only because no durable
/// subscription exists to orphan and every referencing string lives in this
/// repository. Neither will be free again once a customer has one.
public final class BladeEventTypes {

	/// An application instance started. One per app, per node, per restart —
	/// published at `servletInitialized`, before the load balancer sends any
	/// traffic to this node.
	public static final String APPLICATION_STARTED = "org.vorpal.blade.application.started";

	/// An application instance stopped.
	public static final String APPLICATION_STOPPED = "org.vorpal.blade.application.stopped";

	/// A call began. The session is the *call* as it flows through every app in
	/// a chain, so several apps may report the same one; the correlator makes
	/// them the same session rather than competing rows.
	public static final String SESSION_STARTED = "org.vorpal.blade.session.started";

	/// A call ended.
	public static final String SESSION_STOPPED = "org.vorpal.blade.session.stopped";

	/// An index key attached to a call — a configured origin selector that
	/// matched, e.g. a correlation header or a caller number.
	public static final String SESSION_KEY = "org.vorpal.blade.session.key";

	/// An analytics event whose name the framework does not define — one an
	/// operator added to an application's `analytics.events` configuration.
	///
	/// The fallback, and only the fallback. A framework-emitted name resolves to
	/// one of the eleven types below through [#forEventName]; anything else lands
	/// here with its name in the payload, exactly as before, so an existing
	/// customer configuration keeps flowing without a catalog edit.
	public static final String CALL_EVENT = "org.vorpal.blade.call.event";

	// ------------------------------------------------------------------ the call

	/// A call began — `InitialInvite` received an initial INVITE and is placing
	/// the outbound dialog. Published beside `Analytics.sessionStart`.
	public static final String CALL_STARTED = "org.vorpal.blade.call.started";

	/// The callee's dialog returned a success response, on its way back to the
	/// caller.
	public static final String CALL_ANSWERED = "org.vorpal.blade.call.answered";

	/// The caller's ACK was relayed to the callee, completing the handshake.
	///
	/// **After [#CALL_ANSWERED], not before it.** This is the ACK, not a
	/// provisional response — `InitialInvite` publishes it while building
	/// `bobAck`.
	public static final String CALL_CONNECTED = "org.vorpal.blade.call.connected";

	/// An answered call was terminated — `Terminate` saw the dialog in
	/// `CONFIRMED` and is sending BYE.
	public static final String CALL_COMPLETED = "org.vorpal.blade.call.completed";

	/// A call was terminated before it was answered — `Terminate` saw the dialog
	/// in `EARLY` and is cancelling the INVITE.
	public static final String CALL_ABANDONED = "org.vorpal.blade.call.abandoned";

	/// The callee's dialog returned a failure response.
	///
	/// Any failure response, from anywhere on that dialog — an intermediary's 503
	/// counts, so this says the call did not succeed rather than that the callee
	/// personally refused it.
	public static final String CALL_DECLINED = "org.vorpal.blade.call.declined";

	// -------------------------------------------------------------- the transfer

	/// A REFER arrived from the transferor, before anything was done about it.
	///
	/// This is the one an actor subscribes to, and it is a *fact*: the publisher
	/// states that a REFER was received and does not know or care who acts on it.
	/// A transfer app subscribes and performs the transfer; analytics subscribes
	/// to the same fact and records it; neither knows about the other.
	public static final String TRANSFER_REQUESTED = "org.vorpal.blade.transfer.requested";

	/// The transfer is under way — `BlindTransfer` sent the INVITE to the target,
	/// or `ReferTransfer` saw a `100 Trying` sipfrag come back in a NOTIFY.
	public static final String TRANSFER_INITIATED = "org.vorpal.blade.transfer.initiated";

	/// The transfer target answered — a success response to the target INVITE, or
	/// a `200 OK` sipfrag in a NOTIFY.
	public static final String TRANSFER_COMPLETED = "org.vorpal.blade.transfer.completed";

	/// The transfer did not succeed: the target's dialog returned a failure response,
	/// or the REFER itself was refused.
	///
	/// Both cases land here — `ReferTransfer` publishes it for a failed REFER as
	/// well as for a `486` sipfrag — so this means "the transfer was refused",
	/// not specifically "the target said no".
	public static final String TRANSFER_DECLINED = "org.vorpal.blade.transfer.declined";

	/// The transferee gave up before the transfer completed — a BYE or CANCEL
	/// from the transferee, or a `487` from the target because of one.
	public static final String TRANSFER_ABANDONED = "org.vorpal.blade.transfer.abandoned";

	/// Somebody was allowed to touch call content — audio, a transcript, or the
	/// call-identifying data around them.
	///
	/// **Not an analytics event, and deliberately not on that subscription.**
	/// Analytics records what a call did; this records what a *person* did, and
	/// the two answer to different readers with different retention. It is
	/// declared here because it rides the same bus and deserves the same
	/// versioned envelope, not because it belongs in the analytics database.
	public static final String ACCESS_PERMITTED = "org.vorpal.blade.access.permitted";

	/// Somebody was refused. The pair is what makes the log an audit log:
	/// a record of grants alone cannot show attempted overreach, which is most
	/// of what an access review is looking for.
	public static final String ACCESS_DENIED = "org.vorpal.blade.access.denied";

	/// The CloudEvents type for an analytics event name — one of the eleven when
	/// the framework defines the name, [#CALL_EVENT] otherwise.
	///
	/// The one place the mapping lives, so the producer, the catalog and any
	/// consumer that wants to reverse it cannot disagree. Deliberately a `switch`
	/// over constants rather than a lookup table: the compiler checks the right
	/// side, this runs on the SIP container thread, and there is no initialization
	/// order to reason about.
	public static String forEventName(String eventName) {
		if (eventName == null) {
			return CALL_EVENT;
		}
		switch (eventName) {
		case "callStarted":
			return CALL_STARTED;
		case "callAnswered":
			return CALL_ANSWERED;
		case "callConnected":
			return CALL_CONNECTED;
		case "callCompleted":
			return CALL_COMPLETED;
		case "callAbandoned":
			return CALL_ABANDONED;
		case "callDeclined":
			return CALL_DECLINED;
		case "transferRequested":
			return TRANSFER_REQUESTED;
		case "transferInitiated":
			return TRANSFER_INITIATED;
		case "transferCompleted":
			return TRANSFER_COMPLETED;
		case "transferDeclined":
			return TRANSFER_DECLINED;
		case "transferAbandoned":
			return TRANSFER_ABANDONED;
		default:
			// An operator-defined name from an app's analytics.events config.
			return CALL_EVENT;
		}
	}

	private BladeEventTypes() {
	}
}
