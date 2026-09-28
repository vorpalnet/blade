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
	/// The fallback, and only the fallback. A framework-defined name resolves to
	/// its own type below through [#forEventName]; anything else lands here with
	/// its name in the payload's `eventName`, so an existing customer
	/// configuration keeps flowing without a catalog edit.
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

	// ------------------------------------------------------------------ the risk
	//
	// Defined here so the contract is blade's — the type, the payload, the
	// catalog entry — and published by whatever implements the risk tap (today
	// Gryphon's RiskEvents, from a media pipeline the framework never sees). A
	// subscriber that wants the verdict depends on blade only.

	/// The fused call-risk assessment changed: a signal (acoustic, signaling,
	/// provenance, behavior) was scored and folded in. Frequent — one per scored
	/// window while a call is analysed. Carries the band, the score and the
	/// per-signal contributions, so a reader sees why, not just how much.
	public static final String CALL_RISK_ASSESSED = "org.vorpal.blade.call.risk.assessed";

	/// Risk sustained past the debounce: the call is flagged. At most once per
	/// call. Whatever acts on it (a fraud queue, forced recording, a brand
	/// warning) is the subscriber's business; the event exists so the decision
	/// is auditable.
	public static final String CALL_RISK_FLAGGED = "org.vorpal.blade.call.risk.flagged";

	// ------------------------------------------------------------ the transcript

	/// A party said something and a transcriber decoded it: one utterance, as
	/// text. Frequent, one per endpointed utterance while the call is
	/// transcribed. Carries `text`, `party` (caller or callee) and,
	/// when the transcriber has a media clock, `startMs` / `endMs` from when the
	/// tap attached. Published by whatever hears the audio (Gryphon's in-server
	/// ASR tap today); the contract is blade's so a screen or an indexer depends
	/// on blade alone.
	public static final String CALL_UTTERANCE = "org.vorpal.blade.call.utterance";

	/// A party's voice was scored for being synthetic: one window of audio, as
	/// a probability. Periodic, one per scored window while the call is heard.
	/// Carries `score` (0 genuine to 1 synthetic), `party` (caller or
	/// callee), `model` when the scorer names one, and `offsetMs` from when the
	/// assessment attached. A raw measurement, not a verdict: a risk engine
	/// fuses it with the other signals and publishes [#CALL_RISK_ASSESSED].
	public static final String CALL_VOICE_ASSESSED = "org.vorpal.blade.call.voice.assessed";

	/// Someone asked for another party to be brought into a live call: a
	/// supervisor, a specialist, an interpreter. A request, not a result: the
	/// application that holds the call's media dials the party onto the call's
	/// mix if its own rules allow the destination, and ignores it otherwise.
	/// Carries `target` (the SIP address to dial), `label` (the name
	/// the transcript gives the new voice) and `requestedBy`.
	public static final String CALL_PARTY_REQUESTED = "org.vorpal.blade.call.party.requested";

	/// A finished call was reviewed after the fact: a model or a person read
	/// the conversation and labelled it. Carries `labels` (the content labels
	/// found, an array) and optionally `line` and `text`, the line that earned them. One
	/// per review; the agent console shows it on the call's card.
	public static final String CALL_REVIEWED = "org.vorpal.blade.call.reviewed";

	/// The agent who handled a call recorded its disposition: the outcome, the
	/// caller's identity as the agent judged it, and what was done about it
	/// (for example, blocking the number). One per disposition filed.
	public static final String CALL_DISPOSITIONED = "org.vorpal.blade.call.dispositioned";

	/// A proxy chose where the call goes and sent it there: iRouter's forward
	/// route, or the balancer's endpoint. Carries `destination` and whatever
	/// identifies the choice (`endpoint`, `tier`). A proxy that does not
	/// record-route sees only the setup, so its session ends with the INVITE
	/// transaction.
	public static final String CALL_ROUTED = "org.vorpal.blade.call.routed";

	/// A router answered the call itself with a response below 400, such as a
	/// redirect server's 302. Carries `status`.
	public static final String CALL_RESPONDED = "org.vorpal.blade.call.responded";

	/// An application placed a call on someone's behalf (third-party call
	/// control) and it was answered or refused. Carries `party`, `status`.
	public static final String CALL_ORIGINATED = "org.vorpal.blade.call.originated";

	/// A call was parked on hold: answered with inactive media and held open.
	public static final String CALL_HELD = "org.vorpal.blade.call.held";

	/// A parked call left hold, because the caller hung up or the call was
	/// taken elsewhere. Carries `heldMs`.
	public static final String CALL_HOLD_ENDED = "org.vorpal.blade.call.hold.ended";

	/// A caller joined a queue because nothing downstream was free. Carries
	/// `queue` and `depth`, the queue's length with this caller in it.
	public static final String QUEUE_ENTERED = "org.vorpal.blade.queue.entered";

	/// A queued caller was offered to the destination. Carries `queue`,
	/// `waitedMs` and `attempt`: a retryable failure puts the caller back and
	/// the next offer counts up.
	public static final String QUEUE_RELEASED = "org.vorpal.blade.queue.released";

	/// A caller left a queue without being connected. Carries `queue`,
	/// `waitedMs` and `reason`: `caller` when they hung up, `refused` when the
	/// destination refused for good.
	public static final String QUEUE_ABANDONED = "org.vorpal.blade.queue.abandoned";

	/// A prompt or announcement finished playing to a caller. Carries `media`.
	public static final String MEDIA_PLAYED = "org.vorpal.blade.media.played";

	// ------------------------------------------------------------- operations
	//
	// Facts about the platform rather than a call: not persisted by analytics,
	// subscribed to by dashboards and alerting. Each names the `node` that saw it,
	// because every engine keeps its own view.

	/// A queue's length over the last minute: `queue`, `low`, `high`, `depth`
	/// now. Published only for a minute in which the queue held somebody.
	public static final String QUEUE_DEPTH = "org.vorpal.blade.queue.depth";

	/// The balancer stopped offering calls to an endpoint: an OPTIONS ping
	/// failed, or it answered a call 503. Published on the change, not on every
	/// failed ping. Carries `endpoint`, `note`, and `retryAfter` when it asked
	/// for a backoff.
	public static final String ENDPOINT_DOWN = "org.vorpal.blade.endpoint.down";

	/// An endpoint the balancer had marked down answered again.
	public static final String ENDPOINT_UP = "org.vorpal.blade.endpoint.up";

	/// A device registered a contact it did not already have: `aor`, `contact`,
	/// `expires`. A refresh of a known contact is not published.
	public static final String REGISTRATION_ADDED = "org.vorpal.blade.registration.added";

	/// A contact left the registrar: `reason` is `unregistered` for an
	/// Expires 0, `expired` for one found lapsed on the address's next REGISTER.
	public static final String REGISTRATION_REMOVED = "org.vorpal.blade.registration.removed";

	/// A gateway's registration with its carrier trunk succeeded, the first
	/// time or after a failure. Refreshes are not published. Carries `gateway`,
	/// `registrar`, `expires`.
	public static final String TRUNK_REGISTERED = "org.vorpal.blade.trunk.registered";

	/// A gateway's trunk registration failed: `status`, and `reason`. Calls
	/// through it will fail until it recovers.
	public static final String TRUNK_FAILED = "org.vorpal.blade.trunk.failed";

	/// A gateway removed its trunk registration, as it does when stopping.
	public static final String TRUNK_UNREGISTERED = "org.vorpal.blade.trunk.unregistered";

	/// An entity published its presence: `entity`, `event` (the package, e.g.
	/// presence), `expires`, and `basic` (open or closed) when the body is PIDF.
	public static final String PRESENCE_PUBLISHED = "org.vorpal.blade.presence.published";

	/// The SIP access list refused a request: `method`, `sourceAddress`,
	/// `from`, `requestUri`. Not an access record for the audit sink: a scanner
	/// produces thousands, and they answer to network operations.
	public static final String SIP_DENIED = "org.vorpal.blade.sip.denied";

	/// Somebody saved a configuration file from an editor: `actor`, `file`,
	/// `editor`. The change reaches the engines as [#CONFIG_PUBLISHED].
	public static final String CONFIG_SAVED = "org.vorpal.blade.config.saved";

	/// A changed configuration file was pushed to the servers running its
	/// application: `file`, `scope` (domain, cluster or server), `pushedTo`,
	/// and `failed` for any server that did not take it. Published for every
	/// change the Configurator sees, whoever or whatever made it.
	public static final String CONFIG_PUBLISHED = "org.vorpal.blade.config.published";

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

	/// A recorded conversation became a record: its manifest was committed to
	/// the archive, by the node that recorded it or by the sweep that finalises
	/// what a dead node left behind. The payload names the conversation and
	/// nothing else about it; whoever needs the content reads the archive, so
	/// an index built from these events is a cache of the archive and can be
	/// rebuilt from it.
	public static final String CONVERSATION_CLOSED = "org.vorpal.blade.conversation.closed";

	/// Somebody joined or left a messaging room. The application that owns
	/// the room's membership publishes it (a meeting, when it admits a
	/// participant and when they leave); `proto/messaging` applies it, so
	/// the room delivers to exactly the people let in, and replays its stored
	/// messages to a newcomer.
	public static final String ROOM_MEMBER = "org.vorpal.blade.messaging.member";

	/// The CloudEvents type for an analytics event name: its declared type when
	/// the framework defines the name, [#CALL_EVENT] otherwise.
	///
	/// Used by the configuration-driven path only, where an operator names
	/// events in `analytics.events`. Code that publishes an event names its
	/// type directly through [Events]. Deliberately a `switch` over constants
	/// rather than a lookup table: the compiler checks the right side, this runs
	/// on the SIP container thread, and there is no initialization order to
	/// reason about.
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
		case "callRiskAssessed":
			return CALL_RISK_ASSESSED;
		case "callRiskFlagged":
			return CALL_RISK_FLAGGED;
		case "callerSaid":
			return CALL_UTTERANCE;
		case "voiceAssessed":
			return CALL_VOICE_ASSESSED;
		case "partyRequested":
			return CALL_PARTY_REQUESTED;
		case "callReviewed":
			return CALL_REVIEWED;
		case "agentDisposition":
			return CALL_DISPOSITIONED;
		case "callRouted":
			return CALL_ROUTED;
		case "callResponded":
			return CALL_RESPONDED;
		case "callOriginated":
			return CALL_ORIGINATED;
		case "callHeld":
			return CALL_HELD;
		case "callHoldEnded":
			return CALL_HOLD_ENDED;
		case "queueEntered":
			return QUEUE_ENTERED;
		case "queueReleased":
			return QUEUE_RELEASED;
		case "queueAbandoned":
			return QUEUE_ABANDONED;
		case "mediaPlayed":
			return MEDIA_PLAYED;
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

	/// The analytics event name for a type: the inverse of [#forEventName].
	///
	/// **This is the analytics database's `type` column.** The SQL views and the
	/// agent console's queries select on these short names (`callerSaid`,
	/// `callRiskAssessed`), and years of rows already carry them, so the names
	/// stay fixed however the wire type is spelled. A type with no analytics
	/// name falls back to its last dotted segment.
	public static String eventNameOf(String type) {
		if (type == null || type.isEmpty()) {
			return "unknown";
		}
		switch (type) {
		case CALL_STARTED:
			return "callStarted";
		case CALL_ANSWERED:
			return "callAnswered";
		case CALL_CONNECTED:
			return "callConnected";
		case CALL_COMPLETED:
			return "callCompleted";
		case CALL_ABANDONED:
			return "callAbandoned";
		case CALL_DECLINED:
			return "callDeclined";
		case CALL_RISK_ASSESSED:
			return "callRiskAssessed";
		case CALL_RISK_FLAGGED:
			return "callRiskFlagged";
		case CALL_UTTERANCE:
			return "callerSaid";
		case CALL_VOICE_ASSESSED:
			return "voiceAssessed";
		case CALL_PARTY_REQUESTED:
			return "partyRequested";
		case CALL_REVIEWED:
			return "callReviewed";
		case CALL_DISPOSITIONED:
			return "agentDisposition";
		case CALL_ROUTED:
			return "callRouted";
		case CALL_RESPONDED:
			return "callResponded";
		case CALL_ORIGINATED:
			return "callOriginated";
		case CALL_HELD:
			return "callHeld";
		case CALL_HOLD_ENDED:
			return "callHoldEnded";
		case QUEUE_ENTERED:
			return "queueEntered";
		case QUEUE_RELEASED:
			return "queueReleased";
		case QUEUE_ABANDONED:
			return "queueAbandoned";
		case MEDIA_PLAYED:
			return "mediaPlayed";
		case TRANSFER_REQUESTED:
			return "transferRequested";
		case TRANSFER_INITIATED:
			return "transferInitiated";
		case TRANSFER_COMPLETED:
			return "transferCompleted";
		case TRANSFER_DECLINED:
			return "transferDeclined";
		case TRANSFER_ABANDONED:
			return "transferAbandoned";
		default:
			int dot = type.lastIndexOf('.');
			return (dot < 0 || dot == type.length() - 1) ? type : type.substring(dot + 1);
		}
	}

	private BladeEventTypes() {
	}
}
