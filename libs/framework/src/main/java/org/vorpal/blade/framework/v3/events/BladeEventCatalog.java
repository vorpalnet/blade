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
		return types;
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

	/// The extracted attributes, shared by every call-scoped event.
	private static EventField attributes() {
		EventField attrName = new EventField("name", EventFieldType.STRING, true);
		attrName.setDescription("Attribute name.");
		EventField attrValue = new EventField("value", EventFieldType.STRING, true);
		attrValue.setDescription("Attribute value.");

		EventField attributes = new EventField("attributes", EventFieldType.ARRAY, false);
		attributes.setItemType(EventFieldType.OBJECT);
		attributes.setFields(Arrays.asList(attrName, attrValue));
		attributes.setDescription(
				"The attributes this application's configuration extracted from the SIP message. An array of name/value pairs rather than a free-form object, because the field model has no map kind — a map could not be declared, validated or generated from. Names must be unique; the consumer takes the last of any duplicate.");
		return attributes;
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

	/// The payload every call-scoped event carries.
	///
	/// Shared because it is genuinely the same shape, unlike the prose above it.
	/// The correlator is optional rather than required: a sessionless event is
	/// legal, and rejecting one at the ingress would turn a logging gap into a
	/// failed publish on the SIP container thread.
	private static List<EventField> callScopedFields() {
		List<EventField> fields = correlator();
		fields.get(0).setRequired(false);
		fields.get(1).setRequired(false);

		EventField occurredAt = new EventField("occurredAt", EventFieldType.INSTANT, true);
		occurredAt.setDescription("When the event happened, as distinct from when it was published.");
		fields.add(occurredAt);

		fields.addAll(applicationIdentity());
		fields.add(attributes());
		return fields;
	}

	private static EventType callStarted() {
		EventType declaration = base(BladeEventTypes.CALL_STARTED, "Call Started",
				"A call began: an initial INVITE was received and the B2BUA is placing the outbound leg. Published beside Analytics.sessionStart, so it is the first event of a call.",
				"CallStarted");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callAnswered() {
		EventType declaration = base(BladeEventTypes.CALL_ANSWERED, "Call Answered",
				"The callee's leg returned a success response, on its way back to the caller.", "CallAnswered");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callConnected() {
		EventType declaration = base(BladeEventTypes.CALL_CONNECTED, "Call Connected",
				"The caller's ACK was relayed to the callee, completing the handshake. This is the ACK, not a ringing response — it arrives AFTER Call Answered, not before it.",
				"CallConnected");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callCompleted() {
		EventType declaration = base(BladeEventTypes.CALL_COMPLETED, "Call Completed",
				"An answered call was terminated — the dialog was CONFIRMED and a BYE is being sent.",
				"CallCompleted");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callAbandoned() {
		EventType declaration = base(BladeEventTypes.CALL_ABANDONED, "Call Abandoned",
				"A call was terminated before it was answered — the dialog was EARLY and the INVITE is being cancelled.",
				"CallAbandoned");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callDeclined() {
		EventType declaration = base(BladeEventTypes.CALL_DECLINED, "Call Declined",
				"The callee's leg returned a failure response. Any failure from anywhere on that leg counts — an intermediary's 503 as much as a 486 from the endpoint — so this says the call did not succeed, not that the callee personally refused it.",
				"CallDeclined");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferRequested() {
		EventType declaration = base(BladeEventTypes.TRANSFER_REQUESTED, "Transfer Requested",
				"A REFER arrived from the transferor, before anything was done about it. This is a fact, not a command: it says a REFER was received, not that anyone should transfer. A transfer app subscribes and performs the transfer; analytics subscribes to the same fact and records it; neither knows about the other.",
				"TransferRequested");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferInitiated() {
		EventType declaration = base(BladeEventTypes.TRANSFER_INITIATED, "Transfer Initiated",
				"The transfer is under way — a blind transfer sent the INVITE to the target, or a refer transfer saw a 100 Trying sipfrag come back in a NOTIFY.",
				"TransferInitiated");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferCompleted() {
		EventType declaration = base(BladeEventTypes.TRANSFER_COMPLETED, "Transfer Completed",
				"The transfer target answered — a success response to the target INVITE, or a 200 OK sipfrag in a NOTIFY.",
				"TransferCompleted");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferDeclined() {
		EventType declaration = base(BladeEventTypes.TRANSFER_DECLINED, "Transfer Declined",
				"The transfer was refused: either the target's leg returned a failure response, or the REFER itself was refused. Both land here, so this means the transfer did not happen — not specifically that the target said no.",
				"TransferDeclined");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType transferAbandoned() {
		EventType declaration = base(BladeEventTypes.TRANSFER_ABANDONED, "Transfer Abandoned",
				"The transferee gave up before the transfer completed — a BYE or CANCEL from the transferee, or a 487 from the target caused by one.",
				"TransferAbandoned");
		declaration.setFields(callScopedFields());
		return declaration;
	}

	private static EventType callEvent() {
		EventType declaration = base(BladeEventTypes.CALL_EVENT, "Call Event (operator-defined)",
				"An analytics event whose name the framework does not define — one an operator added to an application's analytics.events configuration. The eleven names BLADE emits itself each have their own type; this is the fallback for the rest, so an existing configuration keeps flowing without a catalog edit. The specific name travels in the payload.",
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
		fields.add(attributes());
		declaration.setFields(fields);
		return declaration;
	}

}
