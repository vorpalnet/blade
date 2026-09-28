package org.vorpal.blade.framework.v3.events;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The event types BLADE itself emits, declared so the catalog describes the
/// whole bus rather than only what applications add to it.
///
/// **Why these belong in the catalog.** The analytics stream has always had an
/// event catalog — `Analytics.events`, a `Map<String, EventSelector>` in each
/// app's own config — but it declares only *how to extract* a value, never what
/// shape the value is. The catalog declares the shape. Together they are one
/// complete definition; apart, neither can validate the other.
///
/// **The split is deliberate and stays.** These declarations are the *contract*
/// and are domain-wide: what an event is called, what it carries, where it goes.
/// The *extraction* rules stay per-app in `analytics.events`, because the same
/// logical event legitimately comes from different headers in different
/// applications. The console cross-checks the two and reports drift; it does not
/// merge them.
///
/// **The framework's names are types; an operator's names are payload.** The
/// eleven call and transfer events below are a closed set defined in framework
/// code — `InitialInvite`, `Terminate`, `BlindTransfer` and `ReferTransfer`
/// publish exactly these and no configuration changes it — so each gets a real
/// type and an actor can select on it precisely. A name an *operator* invents in
/// `analytics.events` has no declaration to select on and lands on
/// [BladeEventTypes#CALL_EVENT] with its name in the payload, which is why adding
/// the eleven breaks nobody's existing configuration.
public final class BladeEventCatalog {

	/// The name of the subscription that feeds the analytics database.
	///
	/// A constant because two things must agree on it and they live in different
	/// modules: this declaration, and the `@ActivationConfigProperty` values on
	/// the sink's own MDB in `services/analytics`.
	public static final String ANALYTICS_SUBSCRIPTION = "analytics-db";

	/// Type name → declared [EventType#getVersion], built once from the
	/// declarations below so a publish-time lookup does not rebuild the whole
	/// catalog on the SIP container thread.
	private static final Map<String, Integer> VERSIONS = versionIndex();

	private static Map<String, Integer> versionIndex() {
		Map<String, Integer> versions = new HashMap<>();
		for (EventType declaration : analyticsTypes()) {
			versions.put(declaration.getType(), declaration.getVersion());
		}
		// Access events are not analytics types and must stay off that
		// subscription, but they are framework-declared and so get a stamped
		// dataversion like everything else the framework publishes.
		for (EventType declaration : accessTypes()) {
			versions.put(declaration.getType(), declaration.getVersion());
		}
		for (EventType declaration : conversationTypes()) {
			versions.put(declaration.getType(), declaration.getVersion());
		}
		for (EventType declaration : operationsTypes()) {
			versions.put(declaration.getType(), declaration.getVersion());
		}
		return versions;
	}

	/// The declared payload revision of a framework-emitted type — what the
	/// producer stamps as the envelope's `dataversion`. Null for a type this
	/// catalog does not declare, so an undeclared name publishes an unversioned
	/// envelope rather than lying.
	public static Integer versionOf(String type) {
		return VERSIONS.get(type);
	}

	private BladeEventCatalog() {
	}

	/// Every framework-emitted event type, ready to be added to an
	/// [EventCatalog].
	///
	/// All of them are marked [EventType#isPersist] — they are what the analytics
	/// database is built from — and all carry the call correlator as `subject`
	/// except the application-lifecycle pair, which is not call-scoped.
	public static List<EventType> analyticsTypes() {
		List<EventType> types = new ArrayList<>();
		types.add(applicationStarted());
		types.add(applicationStopped());
		types.add(sessionStarted());
		types.add(sessionStopped());
		types.add(sessionKey());
		types.add(callEvent());
		types.addAll(callAndTransferTypes());
		types.addAll(riskTypes());
		types.add(callUtterance());
		types.add(callVoiceAssessed());
		types.add(callPartyRequested());
		types.add(callReviewed());
		types.add(callDispositioned());
		types.addAll(serviceCallTypes());
		return types;
	}

	/// Every type the framework declares: the analytics types, the access pair,
	/// the conversation types and the operations types. What a console shows as
	/// "what BLADE emits".
	///
	/// **Not the analytics sink's defaults.** [EventCatalogFile#frameworkDefaults]
	/// stays [#analyticsTypes]: the sink persists any type found there, so the
	/// types it must not store are kept out of it.
	public static List<EventType> allTypes() {
		List<EventType> types = analyticsTypes();
		types.addAll(accessTypes());
		types.addAll(conversationTypes());
		types.addAll(operationsTypes());
		return types;
	}

	/// The call-risk pair. Defined here — the contract is blade's — and published
	/// by whatever implements the risk tap (Gryphon's RiskEvents, from a media
	/// pipeline the framework never sees). Call-scoped like the eleven: the
	/// correlator is the subject, and the verdict is riskScore, riskBand,
	/// triggerSignal, suspectStreak, and one `signal.<name>` /
	/// `contribution.<name>` pair per fused signal. The dotted names stay flat
	/// because the analytics views read them by exactly those keys.
	public static List<EventType> riskTypes() {
		List<EventType> types = new ArrayList<>();
		types.add(callRiskAssessed());
		types.add(callRiskFlagged());
		return types;
	}

	private static EventType callRiskAssessed() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_RISK_ASSESSED, "Call Risk Assessed",
				"The fused call-risk assessment changed: a signal was scored and folded in. One per scored window while the call is analysed. Beside the declared fields it carries signal.<name> and contribution.<name> (numbers) for each fused signal, so a reader sees why, not just how much, and whatever extra facts the triggering signal adds (campaignMatches, campaignText).",
				"CallRiskAssessed");
		declaration.setFields(riskFields());
		return declaration;
	}

	private static EventType callRiskFlagged() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_RISK_FLAGGED, "Call Risk Flagged",
				"Risk sustained past the debounce: the call is flagged. At most once per call. Same payload as Call Risk Assessed. Whatever acts on it is the subscriber's business; the event exists so the decision is auditable.",
				"CallRiskFlagged");
		declaration.setFields(riskFields());
		return declaration;
	}

	/// One transcribed utterance. Call-scoped like the risk pair.
	private static EventType callUtterance() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_UTTERANCE, "Call Utterance",
				"A party said something and a transcriber decoded it. One per endpointed utterance while the call is transcribed. A screen shows it as it arrives; an indexer keeps it.",
				"CallUtterance");
		List<EventField> fields = callScopedFields();
		fields.add(field("text", EventFieldType.STRING, true,
				"What was said, verbatim from the transcriber, or redacted when a redactor ran."));
		fields.add(field("party", EventFieldType.STRING, false, "Who spoke: caller or callee."));
		fields.add(field("startMs", EventFieldType.LONG, false,
				"Media-clock start, in milliseconds since the tap attached. Absent when the transcriber has no media clock."));
		fields.add(field("endMs", EventFieldType.LONG, false, "Media-clock end, as startMs."));
		EventField labels = field("labels", EventFieldType.ARRAY, false,
				"Content labels a probe attached to this utterance, e.g. a scam script it matched.");
		labels.setItemType(EventFieldType.STRING);
		fields.add(labels);
		fields.add(field("source", EventFieldType.STRING, false,
				"Which labeller produced the labels, when one did."));
		fields.add(field("intent", EventFieldType.STRING, false,
				"The intent a classifier assigned, when one ran."));
		fields.add(field("entity", EventFieldType.STRING, false, "The entity the classifier extracted."));
		fields.add(field("addressed", EventFieldType.STRING, false,
				"Which agent the utterance was addressed to, in a multi-agent conference; absent for the room."));
		declaration.setFields(fields);
		return declaration;
	}

	/// One scored window of a party's voice. Call-scoped like the utterance.
	private static EventType callVoiceAssessed() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_VOICE_ASSESSED, "Call Voice Assessed",
				"A party's voice was scored for being synthetic. One per scored window while the call is heard. A measurement, not a verdict: a risk engine fuses it and publishes Call Risk Assessed.",
				"CallVoiceAssessed");
		List<EventField> fields = callScopedFields();
		fields.add(field("score", EventFieldType.NUMBER, true, "0 for genuine to 1 for synthetic."));
		fields.add(field("party", EventFieldType.STRING, false, "Whose voice: caller or callee."));
		fields.add(field("model", EventFieldType.STRING, false, "The scorer's model, when it names one."));
		fields.add(field("offsetMs", EventFieldType.LONG, false,
				"Milliseconds since the assessment attached."));
		declaration.setFields(fields);
		return declaration;
	}

	/// A request to bring another party into a live call. Call-scoped.
	private static EventType callPartyRequested() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_PARTY_REQUESTED, "Call Party Requested",
				"Someone asked for another party to be brought into a live call. A request, not a result: the application holding the call's media dials the party onto the call's mix if its rules allow the destination.",
				"CallPartyRequested");
		List<EventField> fields = callScopedFields();
		fields.add(field("target", EventFieldType.STRING, true, "The SIP address to dial."));
		fields.add(field("label", EventFieldType.STRING, false, "The name the transcript gives the new voice."));
		fields.add(field("requestedBy", EventFieldType.STRING, false, "Who asked."));
		declaration.setFields(fields);
		return declaration;
	}

	/// A finished call's review. Call-scoped.
	private static EventType callReviewed() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_REVIEWED, "Call Reviewed",
				"A finished call was reviewed after the fact: a model or a person read the conversation and labelled it. One per review.",
				"CallReviewed");
		List<EventField> fields = callScopedFields();
		EventField labels = field("labels", EventFieldType.ARRAY, false, "The content labels the review found.");
		labels.setItemType(EventFieldType.STRING);
		fields.add(labels);
		fields.add(field("line", EventFieldType.INTEGER, false,
				"The 1-based caller line that shows the labels; 0 when no single line does."));
		fields.add(field("text", EventFieldType.STRING, false, "That line's text, when there is one."));
		declaration.setFields(fields);
		return declaration;
	}

	/// An agent's disposition of a call. Call-scoped, and the correlator may be
	/// absent: an agent can disposition a call the console only knows by number.
	private static EventType callDispositioned() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_DISPOSITIONED, "Call Dispositioned",
				"The agent who handled a call recorded its outcome, and what was done about it. One per disposition filed.",
				"CallDispositioned");
		declaration.setSensitiveFields(Arrays.asList("ani", "notes"));
		List<EventField> fields = callScopedFields();
		fields.add(field("outcome", EventFieldType.STRING, true, "The agent's verdict, e.g. legitimate or scam."));
		fields.add(field("treatment", EventFieldType.STRING, true,
				"What the outcome means for the number's future calls."));
		fields.add(field("blocked", EventFieldType.BOOLEAN, true, "Whether the caller's number was blocked."));
		fields.add(field("agent", EventFieldType.STRING, true, "Who filed it."));
		fields.add(field("identity", EventFieldType.STRING, false, "Who the agent judged the caller to be."));
		fields.add(field("action", EventFieldType.STRING, false, "What the agent asked for, e.g. block."));
		fields.add(field("notes", EventFieldType.STRING, false, "The agent's free text."));
		fields.add(field("reasons", EventFieldType.STRING, false, "Why, as the agent picked from the console."));
		fields.add(field("face", EventFieldType.INTEGER, false, "The verdict face the agent chose on the card, 1 to 7."));
		fields.add(field("ani", EventFieldType.STRING, false, "The caller's number."));
		declaration.setFields(fields);
		return declaration;
	}

	/// The analytics sink's subscription: everything on the bus, durably, with no
	/// selector.
	///
	/// **No selector on purpose, and it is the one place that is right.** A
	/// derived selector freezes the type list at generation time, so a type marked
	/// persisted tomorrow would be silently missed until somebody remembered to
	/// regenerate — and "analytics is quietly missing one event type" is close to
	/// undetectable. The sink instead takes everything and consults
	/// [EventType#isPersist] on the live catalog per message. It pays for that by
	/// holding messages it will drop, against the destination's quota. For the one
	/// consumer whose job is to miss nothing, that is the right side of the trade.
	public static EventSubscription analyticsSubscription() {
		EventSubscription subscription = new EventSubscription(ANALYTICS_SUBSCRIPTION);
		subscription.setDescription("Writes the events flagged 'persist' into the analytics database. Takes "
				+ "everything on the bus and filters in code, so a newly-persistable type is recorded without a "
				+ "redeploy.");
		subscription.setOwner("blade-framework");
		subscription.setDurable(true);
		subscription.setSelectorMode(SelectorMode.NONE);
		subscription.setJavaPackage("org.vorpal.blade.services.analytics.jms");
		subscription.setJavaClassName("AnalyticsEvent");
		return subscription;
	}

	/// The eleven call and transfer events.
	///
	/// Written out one method apiece, like every other declaration in this file.
	/// The titles and descriptions are contract prose — they become the schema
	/// `description` that every consumer reads — so they are stated once by a
	/// human who has read the emit site, never derived from the event name. A
	/// name says `callConnected`; only `InitialInvite` says that it fires on the
	/// ACK rather than on a ringing response.
	///
	/// `BladeEventCatalogTest` asserts that every type constant in
	/// [BladeEventTypes] is declared here, so adding one there without a
	/// declaration fails the build rather than publishing onto a type the catalog
	/// does not know.
	public static List<EventType> callAndTransferTypes() {
		List<EventType> types = new ArrayList<>();
		types.add(callStarted());
		types.add(callAnswered());
		types.add(callConnected());
		types.add(callCompleted());
		types.add(callAbandoned());
		types.add(callDeclined());
		types.add(transferRequested());
		types.add(transferInitiated());
		types.add(transferCompleted());
		types.add(transferDeclined());
		types.add(transferAbandoned());
		return types;
	}

	private static EventType base(String type, String title, String description, String className) {
		EventType declaration = new EventType(type);
		declaration.setTitle(title);
		declaration.setDescription(description);
		declaration.setOwner("blade-framework");
		declaration.setVersion(1);
		// Not `...events.analytics`: these are facts about a call that analytics
		// happens to record, and a transfer app binding to
		// `org.vorpal.blade.events.analytics.TransferRequested` would be importing
		// another subscriber's name for its own payload.
		declaration.setJavaPackage("org.vorpal.blade.events");
		declaration.setJavaClassName(className);
		declaration.setDestinationKind(DestinationKind.TOPIC);
		declaration.setPersist(true);
		return declaration;
	}

	/// [#base] for a call-scoped type: version 2, the flat payload described at
	/// [#callScopedFields].
	private static EventType callScopedBase(String type, String title, String description, String className) {
		EventType declaration = base(type, title, description, className);
		declaration.setVersion(2);
		return declaration;
	}

	/// The risk pair's declared fields. The per-signal `signal.<name>` and
	/// `contribution.<name>` numbers are undeclared: the set of signals is the
	/// risk engine's, not the contract's.
	private static List<EventField> riskFields() {
		List<EventField> fields = callScopedFields();
		fields.add(field("riskScore", EventFieldType.NUMBER, true, "The fused probability the call is fraudulent, 0 to 1."));
		fields.add(field("riskBand", EventFieldType.STRING, true, "clear, watch or suspect."));
		fields.add(field("triggerSignal", EventFieldType.STRING, false, "The signal whose score caused this assessment."));
		fields.add(field("suspectStreak", EventFieldType.INTEGER, false,
				"Consecutive assessments in the suspect band; the flag fires when it passes the debounce."));
		return fields;
	}

	/// Fields every call-scoped type carries: the correlator that identifies the
	/// call, which is also the CloudEvents `subject`.
	private static List<EventField> correlator() {
		EventField vorpalId = new EventField("vorpalId", EventFieldType.STRING, true);
		vorpalId.setDescription(
				"The Vorpal-ID as it appears on the SIP wire — the correlator shared by every app handling this call.");

		EventField startedAt = new EventField("startedAt", EventFieldType.INSTANT, true);
		startedAt.setDescription(
				"When the Vorpal-ID was created, i.e. when the call began. Not the time of this event — that is the envelope's 'time'. Together with vorpalId it identifies the call durably, because Vorpal-IDs are only unique among live sessions and are reused.");

		return new ArrayList<>(Arrays.asList(vorpalId, startedAt));
	}

	private static List<EventField> applicationIdentity() {
		EventField appName = new EventField("appName", EventFieldType.STRING, true);
		appName.setDescription("The deployed application name.");
		EventField domain = new EventField("domain", EventFieldType.STRING, true);
		domain.setDescription("The WebLogic domain.");
		EventField server = new EventField("server", EventFieldType.STRING, true);
		server.setDescription("The server this instance runs on.");
		EventField appStartedAt = new EventField("appStartedAt", EventFieldType.INSTANT, true);
		appStartedAt.setDescription(
				"When this application instance started. An instance is one app, on one server, with one configuration — a restart is a new instance, deliberately.");
		return new ArrayList<>(Arrays.asList(appName, domain, server, appStartedAt));
	}

	private static EventField field(String name, EventFieldType type, boolean required, String description) {
		EventField field = new EventField(name, type, required);
		field.setDescription(description);
		return field;
	}

	private static EventType applicationStarted() {
		EventType declaration = base(BladeEventTypes.APPLICATION_STARTED, "Application Started",
				"An application instance came up. Published at servletInitialized, before the load balancer sends any traffic to this node — which is why nothing downstream has to cope with a session arriving before its application.",
				"ApplicationStarted");
		List<EventField> fields = applicationIdentity();
		EventField host = new EventField("host", EventFieldType.STRING, false);
		host.setDescription("Hostname of the server.");
		EventField tenant = new EventField("tenant", EventFieldType.STRING, false);
		tenant.setDescription("Tenant this instance serves, on a multi-tenant deployment.");
		fields.add(host);
		fields.add(tenant);
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType applicationStopped() {
		EventType declaration = base(BladeEventTypes.APPLICATION_STOPPED, "Application Stopped",
				"An application instance shut down.", "ApplicationStopped");
		List<EventField> fields = applicationIdentity();
		EventField stoppedAt = new EventField("stoppedAt", EventFieldType.INSTANT, true);
		stoppedAt.setDescription("When the instance stopped.");
		fields.add(stoppedAt);
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType sessionStarted() {
		EventType declaration = base(BladeEventTypes.SESSION_STARTED, "Session Started",
				"A call began. A session is the call as it flows through every app in a chain, so several apps may report the same one — the correlator makes them the same session rather than competing rows.",
				"SessionStarted");
		List<EventField> fields = correlator();
		fields.addAll(applicationIdentity());
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType sessionStopped() {
		EventType declaration = base(BladeEventTypes.SESSION_STOPPED, "Session Stopped", "A call ended.",
				"SessionStopped");
		List<EventField> fields = correlator();
		EventField stoppedAt = new EventField("stoppedAt", EventFieldType.INSTANT, true);
		stoppedAt.setDescription("When the call ended.");
		fields.add(stoppedAt);
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType sessionKey() {
		EventType declaration = base(BladeEventTypes.SESSION_KEY, "Session Key",
				"An index key attached to a call — a configured origin selector that matched, such as a correlation header or a caller number. Used to find the call afterwards by something other than its Vorpal-ID.",
				"SessionKeyed");
		List<EventField> fields = correlator();
		fields.addAll(applicationIdentity());
		EventField name = new EventField("name", EventFieldType.STRING, true);
		name.setDescription("The selector id that produced this key.");
		EventField value = new EventField("value", EventFieldType.STRING, true);
		value.setDescription("The matched value. Truncated to the column width by the consumer rather than rejected.");
		fields.add(name);
		fields.add(value);
		declaration.setFields(fields);
		return declaration;
	}

	/// The payload every call-scoped event carries: the correlator, when it
	/// happened, and who published it. The event's own facts sit beside these
	/// in the same flat object, typed. Those are declared per type; the
	/// attributes an application's `analytics.events` configuration extracts
	/// are undeclared text fields, which the schema admits.
	///
	/// The correlator is optional rather than required: a sessionless event is
	/// legal, and rejecting one at the ingress would turn a logging gap into a
	/// failed publish on the SIP container thread.
	///
	/// **Version 2 of every call-scoped type** is this flat shape. Version 1
	/// carried the facts as an `attributes` array of name/value strings, which
	/// [CloudEvent#fields] still reads.
	private static List<EventField> callScopedFields() {
		List<EventField> fields = correlator();
		fields.get(0).setRequired(false);
		fields.get(1).setRequired(false);

		EventField occurredAt = new EventField("occurredAt", EventFieldType.INSTANT, true);
		occurredAt.setDescription("When the event happened, as distinct from when it was published.");
		fields.add(occurredAt);

		fields.addAll(applicationIdentity());
		return fields;
	}

	private static EventType callStarted() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_STARTED, "Call Started",
				"A call began: an initial INVITE was received and the B2BUA is placing the outbound dialog. Published beside Analytics.sessionStart, so it is the first event of a call.",
				"CallStarted");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callAnswered() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_ANSWERED, "Call Answered",
				"The callee's dialog returned a success response, on its way back to the caller.", "CallAnswered");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callConnected() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_CONNECTED, "Call Connected",
				"The caller's ACK was relayed to the callee, completing the handshake. This is the ACK, not a ringing response — it arrives AFTER Call Answered, not before it.",
				"CallConnected");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callCompleted() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_COMPLETED, "Call Completed",
				"An answered call was terminated — the dialog was CONFIRMED and a BYE is being sent.",
				"CallCompleted");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callAbandoned() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_ABANDONED, "Call Abandoned",
				"A call was terminated before it was answered — the dialog was EARLY and the INVITE is being cancelled.",
				"CallAbandoned");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callDeclined() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_DECLINED, "Call Declined",
				"The callee's dialog returned a failure response. Any failure from anywhere on that dialog counts — an intermediary's 503 as much as a 486 from the endpoint — so this says the call did not succeed, not that the callee personally refused it.",
				"CallDeclined");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferRequested() {
		EventType declaration = callScopedBase(BladeEventTypes.TRANSFER_REQUESTED, "Transfer Requested",
				"A REFER arrived from the transferor, before anything was done about it. This is a fact, not a command: it says a REFER was received, not that anyone should transfer. A transfer app subscribes and performs the transfer; analytics subscribes to the same fact and records it; neither knows about the other.",
				"TransferRequested");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferInitiated() {
		EventType declaration = callScopedBase(BladeEventTypes.TRANSFER_INITIATED, "Transfer Initiated",
				"The transfer is under way — a blind transfer sent the INVITE to the target, or a refer transfer saw a 100 Trying sipfrag come back in a NOTIFY.",
				"TransferInitiated");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferCompleted() {
		EventType declaration = callScopedBase(BladeEventTypes.TRANSFER_COMPLETED, "Transfer Completed",
				"The transfer target answered — a success response to the target INVITE, or a 200 OK sipfrag in a NOTIFY.",
				"TransferCompleted");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferDeclined() {
		EventType declaration = callScopedBase(BladeEventTypes.TRANSFER_DECLINED, "Transfer Declined",
				"The transfer was refused: either the target's dialog returned a failure response, or the REFER itself was refused. Both land here, so this means the transfer did not happen — not specifically that the target said no.",
				"TransferDeclined");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferAbandoned() {
		EventType declaration = callScopedBase(BladeEventTypes.TRANSFER_ABANDONED, "Transfer Abandoned",
				"The transferee gave up before the transfer completed — a BYE or CANCEL from the transferee, or a 487 from the target caused by one.",
				"TransferAbandoned");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callEvent() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_EVENT, "Call Event (operator-defined)",
				"An analytics event whose name the framework does not define — one an operator added to an application's analytics.events configuration. The names BLADE emits itself each have their own type; this is the fallback for the rest, so an existing configuration keeps flowing without a catalog edit. The specific name travels in the payload, and the extracted attributes as text fields beside it.",
				"CallEvent");
		List<EventField> fields = correlator();
		fields.get(0).setRequired(false);
		fields.get(1).setRequired(false);

		EventField eventName = new EventField("eventName", EventFieldType.STRING, true);
		eventName.setDescription("The analytics event name as the operator configured it.");

		EventField occurredAt = new EventField("occurredAt", EventFieldType.INSTANT, true);
		occurredAt.setDescription("When the event happened, as distinct from when it was published.");

		fields.add(eventName);
		fields.add(occurredAt);
		fields.addAll(applicationIdentity());
		declaration.setFields(fields);
		return declaration;
	}

	/// The access-audit types: who was allowed, who was refused, and why.
	///
	/// **Kept out of [#analyticsTypes] on purpose.** That list is what the
	/// analytics database is built from, and every member of it is marked
	/// persist. An access record answers to a different reader — an access
	/// review, not a traffic report — and is kept under a different retention,
	/// usually a longer one. It also has an integrity requirement the analytics
	/// tables do not: the sink that stores it should hold INSERT and SELECT and
	/// nothing else, so that the people it records cannot edit it. Routing it
	/// into the same subscription would quietly give it the analytics table's
	/// grants and the analytics table's lifetime.
	///
	/// **Nothing here is a sensitive field.** Every other framework type masks
	/// caller identity in the console. These do the opposite deliberately: the
	/// actor's name *is* the record. What must never appear is the content —
	/// naming the recording is the point, carrying the transcript would defeat
	/// it — and that is enforced by the payload shape below, which has nowhere
	/// to put one.
	public static List<EventType> accessTypes() {
		List<EventType> types = new ArrayList<>();
		types.add(accessDecision(BladeEventTypes.ACCESS_PERMITTED, "Access Permitted",
				"A caller was allowed to list, read, play, export or unredact call content. Carries the rule that granted it, so an access review can ask why without reconstructing the policy as it stood that day.",
				"AccessPermitted"));
		types.add(accessDecision(BladeEventTypes.ACCESS_DENIED, "Access Denied",
				"A caller was refused. Published for the same reasons as the grant: a log of successes cannot show attempted overreach, and a run of denials against one record is the signal an access review exists to find.",
				"AccessDenied"));
		return types;
	}

	private static EventType accessDecision(String type, String title, String description, String className) {
		EventType declaration = base(type, title, description, className);
		// Not the analytics database's to store. The audit sink subscribes to
		// these by selector and owns their retention.
		declaration.setPersist(false);
		declaration.setFields(accessFields());
		return declaration;
	}

	private static List<EventField> accessFields() {
		EventField actor = new EventField("actor", EventFieldType.STRING, true);
		actor.setDescription(
				"The authenticated caller, as the identity provider named them. Never masked: an access record whose subject is hidden records nothing.");

		EventField action = new EventField("action", EventFieldType.STRING, true);
		action.setDescription(
				"The permission attempted, e.g. phi:play. Present on a refusal too — a denial that does not say what was attempted is not reviewable.");

		EventField resourceKind = new EventField("resourceKind", EventFieldType.STRING, true);
		resourceKind.setDescription("What sort of thing was reached for, e.g. recording, transcript, accessLog.");

		EventField resourceId = new EventField("resourceId", EventFieldType.STRING, true);
		resourceId.setDescription(
				"Which one. An identifier, never the content: this field names the recording and must never carry a word of it.");

		EventField decision = new EventField("decision", EventFieldType.STRING, true);
		decision.setDescription("permit, deny, or breakglass.");

		EventField rule = new EventField("rule", EventFieldType.STRING, false);
		rule.setDescription(
				"The rule that granted access, by its configured name. Absent on a refusal, because no rule did.");

		EventField reason = new EventField("reason", EventFieldType.STRING, false);
		reason.setDescription(
				"Why it was refused, or the stated justification on a break-glass grant. Absent on an ordinary grant.");

		EventField sourceAddress = new EventField("sourceAddress", EventFieldType.STRING, false);
		sourceAddress.setDescription("The client address the request arrived from, where the caller knows it.");

		return new ArrayList<>(Arrays.asList(actor, action, resourceKind, resourceId, decision, rule, reason,
				sourceAddress));
	}

	/// The conversation lifecycle: a recorded conversation closing, and a
	/// member joining or leaving a messaging room. Not analytics types: the
	/// catalog application subscribes to the first by selector and builds the
	/// searchable index from the archive it points at; `proto/messaging`
	/// subscribes to the second.
	public static List<EventType> conversationTypes() {
		EventType closed = base(BladeEventTypes.CONVERSATION_CLOSED, "Conversation Closed",
				"A recorded conversation was committed to the archive and can be indexed. Names the conversation and the call; carries no content, so an index is rebuilt from the archive, never from these events.",
				"ConversationClosed");
		closed.setPersist(false);
		EventField conversation = new EventField("conversation", EventFieldType.STRING, true);
		conversation.setDescription("The conversation's identifier, which names its prefix in the archive.");
		EventField call = new EventField("call", EventFieldType.STRING, false);
		call.setDescription("The call the conversation belongs to; a transferred call has several conversations under one call.");
		EventField node = new EventField("node", EventFieldType.STRING, false);
		node.setDescription("Which node committed it.");
		EventField reason = new EventField("reason", EventFieldType.STRING, true);
		reason.setDescription("closed for an orderly end, swept for a conversation finalised after its node was lost.");
		closed.setFields(new ArrayList<>(Arrays.asList(conversation, call, node, reason)));

		EventType member = base(BladeEventTypes.ROOM_MEMBER, "Room Member",
				"Somebody joined or left a messaging room. The room delivers messages to its members and replays what it stored to a newcomer.",
				"RoomMember");
		member.setPersist(false);
		EventField room = new EventField("room", EventFieldType.STRING, true);
		room.setDescription("The room, as its SIP address names it: a meeting's room is the meeting id.");
		EventField address = new EventField("address", EventFieldType.STRING, true);
		address.setDescription("The member's address of record, where the room sends their MESSAGEs.");
		EventField participant = new EventField("participant", EventFieldType.STRING, true);
		participant.setDescription("Which of the address's devices this is; one address may be in a room twice.");
		EventField displayName = new EventField("displayName", EventFieldType.STRING, false);
		displayName.setDescription("The member's name, stamped on what they post.");
		EventField joined = new EventField("joined", EventFieldType.BOOLEAN, true);
		joined.setDescription("true when they joined, false when they left.");
		EventField accept = new EventField("accept", EventFieldType.ARRAY, false);
		accept.setItemType(EventFieldType.STRING);
		accept.setDescription("The message formats (media types) this member takes, most preferred first. The room sends each message in the sender's format when it is accepted, else transcodes to the first it can write. Absent: the room sends the sender's format and learns from a 415.");
		member.setFields(new ArrayList<>(Arrays.asList(room, address, participant, displayName, joined, accept)));
		return new ArrayList<>(Arrays.asList(closed, member));
	}

	/// The call-scoped facts BLADE's own services publish: routing, third-party
	/// calls, hold, queueing and prompts. Persisted like the eleven.
	public static List<EventType> serviceCallTypes() {
		List<EventType> types = new ArrayList<>();
		types.add(callRouted());
		types.add(callResponded());
		types.add(callOriginated());
		types.add(callHeld());
		types.add(callHoldEnded());
		types.add(queueEntered());
		types.add(queueReleased());
		types.add(queueAbandoned());
		types.add(mediaPlayed());
		return types;
	}

	private static EventType callRouted() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_ROUTED, "Call Routed",
				"A proxy chose where the call goes and sent it there: iRouter's forward route or the balancer's endpoint. A proxy that does not record-route sees only the setup, so its session ends with the INVITE transaction.",
				"CallRouted");
		List<EventField> fields = callScopedFields();
		fields.add(field("destination", EventFieldType.STRING, false, "The URI the call was sent to."));
		fields.add(field("endpoint", EventFieldType.STRING, false, "The balancer endpoint's configured name."));
		fields.add(field("tier", EventFieldType.STRING, false, "The balancer tier the endpoint belongs to."));
		fields.add(field("failovers", EventFieldType.INTEGER, false, "Tiers tried and failed before this one."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType callResponded() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_RESPONDED, "Call Responded",
				"A router answered the call itself with a response below 400, such as a redirect server's 302.",
				"CallResponded");
		List<EventField> fields = callScopedFields();
		fields.add(field("status", EventFieldType.INTEGER, false, "The response code sent."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType callOriginated() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_ORIGINATED, "Call Originated",
				"An application placed a call on someone's behalf (third-party call control) and the party answered or refused it.",
				"CallOriginated");
		List<EventField> fields = callScopedFields();
		fields.add(field("party", EventFieldType.STRING, true, "Who was called."));
		fields.add(field("status", EventFieldType.INTEGER, true, "The party's final response code."));
		fields.add(field("answered", EventFieldType.BOOLEAN, true, "Whether the party answered."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType callHeld() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_HELD, "Call Held",
				"A call was parked on hold: answered with inactive media and held open until it is taken elsewhere or the caller hangs up.",
				"CallHeld");
		List<EventField> fields = callScopedFields();
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType callHoldEnded() {
		EventType declaration = callScopedBase(BladeEventTypes.CALL_HOLD_ENDED, "Call Hold Ended",
				"A parked call left hold, because the caller hung up or the call was taken elsewhere.",
				"CallHoldEnded");
		List<EventField> fields = callScopedFields();
		fields.add(field("heldMs", EventFieldType.LONG, false, "How long the call was parked."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType queueEntered() {
		EventType declaration = callScopedBase(BladeEventTypes.QUEUE_ENTERED, "Queue Entered",
				"A caller joined a queue because nothing downstream was free. The caller hears ringing, and an announcement once the queue's ring duration passes.",
				"QueueEntered");
		List<EventField> fields = callScopedFields();
		fields.add(field("queue", EventFieldType.STRING, true, "The queue's configured id."));
		fields.add(field("depth", EventFieldType.INTEGER, true, "The queue's length with this caller in it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType queueReleased() {
		EventType declaration = callScopedBase(BladeEventTypes.QUEUE_RELEASED, "Queue Released",
				"A queued caller was offered to the destination. A retryable failure puts the caller back and the next offer counts up.",
				"QueueReleased");
		List<EventField> fields = callScopedFields();
		fields.add(field("queue", EventFieldType.STRING, true, "The queue's configured id."));
		fields.add(field("waitedMs", EventFieldType.LONG, true, "Time since the caller joined the queue."));
		fields.add(field("attempt", EventFieldType.INTEGER, true, "Which offer this is, from 1."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType queueAbandoned() {
		EventType declaration = callScopedBase(BladeEventTypes.QUEUE_ABANDONED, "Queue Abandoned",
				"A caller left a queue without being connected.",
				"QueueAbandoned");
		List<EventField> fields = callScopedFields();
		fields.add(field("queue", EventFieldType.STRING, true, "The queue's configured id."));
		fields.add(field("waitedMs", EventFieldType.LONG, true, "Time since the caller joined the queue."));
		fields.add(field("reason", EventFieldType.STRING, true, "caller when they hung up, refused when the destination refused for good."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType mediaPlayed() {
		EventType declaration = callScopedBase(BladeEventTypes.MEDIA_PLAYED, "Media Played",
				"A prompt or announcement finished playing to a caller.",
				"MediaPlayed");
		List<EventField> fields = callScopedFields();
		fields.add(field("media", EventFieldType.STRING, true, "The media that played."));
		declaration.setFields(fields);
		return declaration;
	}

	/// Facts about the platform rather than a call: queue depth, endpoint and
	/// trunk health, registrations, presence, SIP access refusals and
	/// configuration changes. Not persisted: dashboards and alerting subscribe.
	/// Each carries the `node` that saw it, because every engine keeps its own
	/// view.
	public static List<EventType> operationsTypes() {
		List<EventType> types = new ArrayList<>();
		types.add(queueDepth());
		types.add(endpointDown());
		types.add(endpointUp());
		types.add(registrationAdded());
		types.add(registrationRemoved());
		types.add(trunkRegistered());
		types.add(trunkFailed());
		types.add(trunkUnregistered());
		types.add(presencePublished());
		types.add(sipDenied());
		types.add(configSaved());
		types.add(configPublished());
		return types;
	}

	private static EventType queueDepth() {
		EventType declaration = base(BladeEventTypes.QUEUE_DEPTH, "Queue Depth",
				"A queue's length over the last minute. Published only for a minute in which the queue held somebody.",
				"QueueDepth");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("queue", EventFieldType.STRING, true, "The queue's configured id."));
		fields.add(field("low", EventFieldType.INTEGER, true, "Fewest callers waiting during the minute."));
		fields.add(field("high", EventFieldType.INTEGER, true, "Most callers waiting during the minute."));
		fields.add(field("depth", EventFieldType.INTEGER, true, "Callers waiting now."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType endpointDown() {
		EventType declaration = base(BladeEventTypes.ENDPOINT_DOWN, "Endpoint Down",
				"The balancer stopped offering calls to an endpoint: an OPTIONS ping failed or it answered a call 503. Published on the change, not on every failed ping.",
				"EndpointDown");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("endpoint", EventFieldType.STRING, true, "The endpoint's configured name."));
		fields.add(field("note", EventFieldType.STRING, false, "What was observed, e.g. OPTIONS 408."));
		fields.add(field("retryAfter", EventFieldType.INTEGER, false, "Seconds the endpoint asked to be left alone."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType endpointUp() {
		EventType declaration = base(BladeEventTypes.ENDPOINT_UP, "Endpoint Up",
				"An endpoint the balancer had marked down answered again.",
				"EndpointUp");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("endpoint", EventFieldType.STRING, true, "The endpoint's configured name."));
		fields.add(field("note", EventFieldType.STRING, false, "What was observed, e.g. OPTIONS 200."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType registrationAdded() {
		EventType declaration = base(BladeEventTypes.REGISTRATION_ADDED, "Registration Added",
				"A device registered a contact the registrar did not already have. A refresh of a known contact is not published.",
				"RegistrationAdded");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("aor", EventFieldType.STRING, true, "The address of record."));
		fields.add(field("contact", EventFieldType.STRING, true, "The contact URI."));
		fields.add(field("expires", EventFieldType.INTEGER, true, "Seconds the registration lasts."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType registrationRemoved() {
		EventType declaration = base(BladeEventTypes.REGISTRATION_REMOVED, "Registration Removed",
				"A contact left the registrar.",
				"RegistrationRemoved");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("aor", EventFieldType.STRING, true, "The address of record."));
		fields.add(field("contact", EventFieldType.STRING, true, "The contact URI."));
		fields.add(field("reason", EventFieldType.STRING, true, "unregistered for an Expires 0, expired for one found lapsed on the address's next REGISTER."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType trunkRegistered() {
		EventType declaration = base(BladeEventTypes.TRUNK_REGISTERED, "Trunk Registered",
				"A gateway's registration with its carrier trunk succeeded, the first time or after a failure. Refreshes are not published.",
				"TrunkRegistered");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("gateway", EventFieldType.STRING, true, "The gateway's configured name."));
		fields.add(field("registrar", EventFieldType.STRING, true, "The carrier's registrar domain."));
		fields.add(field("expires", EventFieldType.INTEGER, false, "Seconds the registration lasts."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType trunkFailed() {
		EventType declaration = base(BladeEventTypes.TRUNK_FAILED, "Trunk Failed",
				"A gateway's trunk registration failed. Calls through it fail until it recovers. Published on the change, not on every retry.",
				"TrunkFailed");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("gateway", EventFieldType.STRING, true, "The gateway's configured name."));
		fields.add(field("registrar", EventFieldType.STRING, true, "The carrier's registrar domain."));
		fields.add(field("status", EventFieldType.INTEGER, true, "The registrar's response code."));
		fields.add(field("reason", EventFieldType.STRING, false, "Its reason phrase, or why the gateway gave up."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType trunkUnregistered() {
		EventType declaration = base(BladeEventTypes.TRUNK_UNREGISTERED, "Trunk Unregistered",
				"A gateway removed its trunk registration, as it does when stopping.",
				"TrunkUnregistered");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("gateway", EventFieldType.STRING, true, "The gateway's configured name."));
		fields.add(field("registrar", EventFieldType.STRING, true, "The carrier's registrar domain."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType presencePublished() {
		EventType declaration = base(BladeEventTypes.PRESENCE_PUBLISHED, "Presence Published",
				"An entity published its presence state.",
				"PresencePublished");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("entity", EventFieldType.STRING, true, "Whose presence it is."));
		fields.add(field("event", EventFieldType.STRING, false, "The event package, e.g. presence."));
		fields.add(field("expires", EventFieldType.INTEGER, false, "Seconds the state lasts."));
		fields.add(field("basic", EventFieldType.STRING, false, "open or closed, when the body is PIDF."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType sipDenied() {
		EventType declaration = base(BladeEventTypes.SIP_DENIED, "SIP Denied",
				"The SIP access list refused a request. Not an access record for the audit sink: a scanner produces thousands, and they answer to network operations.",
				"SipDenied");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("method", EventFieldType.STRING, true, "The request method."));
		fields.add(field("sourceAddress", EventFieldType.STRING, true, "Where it came from."));
		fields.add(field("from", EventFieldType.STRING, false, "Its From address."));
		fields.add(field("requestUri", EventFieldType.STRING, false, "What it asked for."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType configSaved() {
		EventType declaration = base(BladeEventTypes.CONFIG_SAVED, "Config Saved",
				"Somebody saved a configuration file from an editor. The change reaches the engines as Config Published.",
				"ConfigSaved");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("actor", EventFieldType.STRING, true, "Who saved it, as the container authenticated them."));
		fields.add(field("file", EventFieldType.STRING, true, "The file, relative to the configuration directory; relative to the domain directory from the files editor."));
		fields.add(field("editor", EventFieldType.STRING, true, "Which editor: configurator, flow, files."));
		fields.add(field("change", EventFieldType.STRING, true, "saved, restored (an earlier version put back) or deleted."));
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}

	private static EventType configPublished() {
		EventType declaration = base(BladeEventTypes.CONFIG_PUBLISHED, "Config Published",
				"A changed configuration file was pushed to the servers running its application. Published for every change the Configurator sees, whoever or whatever made it.",
				"ConfigPublished");
		declaration.setPersist(false);
		List<EventField> fields = new ArrayList<>();
		fields.add(field("file", EventFieldType.STRING, true, "The file, relative to the configuration directory."));
		fields.add(field("application", EventFieldType.STRING, true, "The application it configures."));
		fields.add(field("scope", EventFieldType.STRING, true, "domain, cluster or server; all for an explicit publish of every level."));
		EventField pushedToField = field("pushedTo", EventFieldType.ARRAY, false, "Servers that reloaded it.");
		pushedToField.setItemType(EventFieldType.STRING);
		fields.add(pushedToField);
		EventField failedField = field("failed", EventFieldType.ARRAY, false, "Servers that did not.");
		failedField.setItemType(EventFieldType.STRING);
		fields.add(failedField);
		fields.add(field("node", EventFieldType.STRING, true, "The server that saw it."));
		declaration.setFields(fields);
		return declaration;
	}
}
