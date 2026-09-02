package org.vorpal.blade.services.events;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.vorpal.blade.framework.v3.events.BladeEventCatalog;
import org.vorpal.blade.framework.v3.events.BladeEventTypes;
import org.vorpal.blade.framework.v3.events.DestinationKind;
import org.vorpal.blade.framework.v3.events.EventBus;
import org.vorpal.blade.framework.v3.events.EventCatalog;
import org.vorpal.blade.framework.v3.events.EventField;
import org.vorpal.blade.framework.v3.events.EventFieldType;
import org.vorpal.blade.framework.v3.events.EventSubscription;
import org.vorpal.blade.framework.v3.events.EventType;
import org.vorpal.blade.framework.v3.events.ValidationPolicy;

/// The catalog a fresh install starts from — written to
/// `config/custom/vorpal/_samples/events.json.SAMPLE` by the `SettingsManager`,
/// and what the Configurator offers as a starting point.
///
/// It declares one real event type rather than an empty list, because an empty
/// catalog teaches nothing: an operator opening the designer for the first time
/// should see a worked example of what a declaration looks like — a snake_case
/// wire field, a required field, a description that becomes Javadoc — and be
/// able to generate from it immediately.
///
/// The example is the attendant's meeting event, which is a real one: the
/// attendant sidecar already emits this envelope over HTTP, so pointing its sink
/// at this service publishes it with no producer change.
///
/// **Every declaration here matches code that actually ships**, rather than
/// naming a plausible `com.example` package. `CalendarEventListener` really is at
/// `org.vorpal.blade.services.events.CalendarEventListener`, and
/// `TransferEventListener` really is in `services/transfer`. An operator will
/// replace both with their own packages — but a sample catalog that describes
/// files nobody can open is the exact drift this subsystem exists to abolish,
/// and it would be an odd place to start abolishing it.
public class EventCatalogSample extends EventCatalog {

	private static final long serialVersionUID = 1L;

	public EventCatalogSample() {
		this.setDefaultSource("/blade/events");
		this.setConnectionFactoryJndi(EventBus.CONNECTION_FACTORY_JNDI);
		this.setDefaultDestinationJndi(EventBus.TOPIC_JNDI);
		this.setValidation(ValidationPolicy.WARN);
		this.setRejectUnknownTypes(false);

		EventType meeting = new EventType("net.vorpal.attendant.meeting.scheduled");
		meeting.setTitle("Meeting Scheduled");
		meeting.setDescription(
				"Emitted when the attendant has confirmed a meeting with the caller. A calendar app subscribes to this and creates the appointment.");
		meeting.setOwner("attendant");
		meeting.setVersion(1);
		meeting.setDestinationJndi(EventBus.TOPIC_JNDI);
		meeting.setDestinationKind(DestinationKind.TOPIC);
		meeting.setJavaPackage("org.vorpal.blade.services.events");
		meeting.setJavaClassName("MeetingScheduled");

		EventField who = new EventField("who", EventFieldType.STRING, true);
		who.setDescription("Who the meeting is with, as the caller named them.");

		EventField whenText = new EventField("when_text", EventFieldType.STRING, true);
		whenText.setDescription("When the meeting is, in the caller's own words — 'next Tuesday at 3'.");

		EventField location = new EventField("location", EventFieldType.STRING, false);
		location.setDescription("Where, if the caller said.");

		meeting.setFields(Arrays.asList(who, whenText, location));

		// The framework's own event types come first, so a fresh install can see
		// what BLADE already emits before adding anything. The attendant example
		// follows as a worked case of an application-defined type.
		List<EventType> types = new ArrayList<>(BladeEventCatalog.analyticsTypes());
		types.add(meeting);
		this.setTypes(types);

		// Three subscribers, and the point is the overlap. `transfer` and
		// `analytics-db` both receive every transfer event: the actor because its
		// selector names those types, the sink because it selects nothing and
		// filters in code. Neither consumes the other's copy — a topic fans out,
		// and their subscription identities differ. That is the whole reason
		// subscriptions are declared here rather than derived from event types.
		EventSubscription calendar = new EventSubscription("calendar");
		calendar.setDescription(
				"Creates the appointment when the attendant confirms a meeting. An actor: it names the one type it handles, so the broker filters and this app never wakes for anything else.");
		calendar.setOwner("attendant");
		calendar.setTypes(Arrays.asList("net.vorpal.attendant.meeting.scheduled"));
		calendar.setDurable(true);
		calendar.setJavaPackage("org.vorpal.blade.services.events");
		calendar.setJavaClassName("CalendarEvent");

		EventSubscription transfer = new EventSubscription("transfer");
		transfer.setDescription(
				"Watches the transfers this domain performs. The same five events also reach analytics-db, which records them — two applications, one event, each with its own copy.");
		transfer.setOwner("blade-framework");
		transfer.setTypes(Arrays.asList(BladeEventTypes.TRANSFER_REQUESTED, BladeEventTypes.TRANSFER_INITIATED,
				BladeEventTypes.TRANSFER_COMPLETED, BladeEventTypes.TRANSFER_DECLINED,
				BladeEventTypes.TRANSFER_ABANDONED));
		transfer.setDurable(true);
		transfer.setJavaPackage("org.vorpal.blade.services.transfer.events");
		transfer.setJavaClassName("TransferEvent");

		this.setSubscriptions(
				Arrays.asList(calendar, transfer, BladeEventCatalog.analyticsSubscription()));
	}
}
