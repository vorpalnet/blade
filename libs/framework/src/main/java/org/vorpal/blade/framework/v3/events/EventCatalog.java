package org.vorpal.blade.framework.v3.events;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// The registry of every event type on the BLADE event bus — the one place that
/// says what an event is, what it carries, and where it flows.
///
/// Published as `config/custom/vorpal/events.json` by the console on the
/// AdminServer and reloaded by the engine tier through the ordinary
/// `SettingsManager` path, the same mechanism the FSMAR editor uses for
/// `fsmar.json`. That means the catalog gets versioned history, cluster
/// propagation and a Configurator fallback editor without a line of new
/// plumbing.
///
/// **The JMS names are policy, the event types are content.** The connection
/// factory and default destination live here so an operator can point the bus at
/// resources they provisioned themselves; they default to the constants in
/// [EventBus] so an untouched install behaves exactly as before.
///
/// **Types are a list, not a map keyed by type.** A map would carry the event
/// type in both the key and the value, which is a drift bug waiting to happen —
/// in the one config file whose entire purpose is eliminating drift.
///
/// **Two lists, because an event and an interest in it are different things.**
/// [EventType] says what an event is; [EventSubscription] says who listens and to
/// what. Keeping subscriber identity off the event type is what lets several apps
/// receive the same event — the transfer app acting on a refer while analytics
/// records it — each with its own copy rather than competing for one stream.
@SchemaAbout(
		name = "Event Bus",
		tagline = "CloudEvents pub/sub for BLADE apps",
		description = "The catalog of event types carried on the BLADE v3 event bus. Each declaration names an event, describes its payload, and binds it to a JMS destination — and everything else is derived from that: the generated payload class and its JSON Schema, the consumer's message selector, the destination the console provisions, and the validation the ingress applies.")
public class EventCatalog extends Configuration implements Serializable {

	private static final long serialVersionUID = 1L;

	private String defaultSource = "/blade/events";
	private String connectionFactoryJndi = EventBus.CONNECTION_FACTORY_JNDI;
	private String defaultDestinationJndi = EventBus.TOPIC_JNDI;
	private ValidationPolicy validation = ValidationPolicy.WARN;
	private boolean rejectUnknownTypes;
	private List<EventType> types;
	private List<EventSubscription> subscriptions;

	/// Find a declaration by its CloudEvents `type`, or null when the catalog
	/// does not know it.
	@JsonIgnore
	public EventType findType(String type) {
		if (type == null) {
			return null;
		}
		for (EventType candidate : typesOrEmpty()) {
			if (type.equals(candidate.getType())) {
				return candidate;
			}
		}
		return null;
	}

	/// Find a subscription by name, or null when the catalog does not declare it.
	@JsonIgnore
	public EventSubscription findSubscription(String name) {
		if (name == null) {
			return null;
		}
		for (EventSubscription candidate : subscriptionsOrEmpty()) {
			if (name.equals(candidate.getName())) {
				return candidate;
			}
		}
		return null;
	}

	/// The destination JNDI name an event of this type is published to — the
	/// declaration's own, falling back to [#getDefaultDestinationJndi].
	@JsonIgnore
	public String destinationFor(EventType declaration) {
		if (declaration != null) {
			String jndi = declaration.getDestinationJndi();
			if (jndi != null && !jndi.isEmpty()) {
				return jndi;
			}
		}
		return defaultDestinationJndi;
	}

	/// The destination a subscription binds to, derived from the types it
	/// declares rather than configured on the subscription itself.
	///
	/// **Derived, not declared, so there is nothing to keep in sync.** A
	/// subscription naming a destination of its own would be a second place the
	/// binding lives, free to drift from where the events actually go — in the one
	/// config file whose whole purpose is eliminating drift. A subscription whose
	/// types disagree about their destination is therefore not resolved silently:
	/// it is a catalog finding, reported by [#validate], and this method returns
	/// the first type's destination so a preview still renders.
	@JsonIgnore
	public String destinationForSubscription(EventSubscription subscription) {
		if (subscription != null) {
			for (String type : subscription.typesOrEmpty()) {
				EventType declared = findType(type);
				if (declared != null) {
					return destinationFor(declared);
				}
			}
		}
		return defaultDestinationJndi;
	}

	/// Whether a subscription's destination is a topic or a queue, derived the
	/// same way [#destinationForSubscription] is.
	///
	/// Defaults to `TOPIC`: a subscription naming no types is a sink on the main
	/// bus, and the main bus fans out.
	@JsonIgnore
	public DestinationKind destinationKindForSubscription(EventSubscription subscription) {
		if (subscription != null) {
			for (String type : subscription.typesOrEmpty()) {
				EventType declared = findType(type);
				if (declared != null) {
					return declared.getDestinationKind();
				}
			}
		}
		return DestinationKind.TOPIC;
	}

	/// True when the generated consumer for this subscription should declare a
	/// durable subscription: the subscription asks for one *and* its destination
	/// is a topic. Durability is meaningless on a queue, where the message is
	/// already retained until somebody consumes it.
	@JsonIgnore
	public boolean isDurableTopic(EventSubscription subscription) {
		return subscription != null && subscription.isDurable()
				&& destinationKindForSubscription(subscription) != DestinationKind.QUEUE;
	}

	/// Everything wrong with this catalog, in the operator's language — empty when
	/// there is nothing to say.
	///
	/// This is the check that would have caught the defect that prompted
	/// subscriptions to exist at all, so it earns its place: **two subscriptions
	/// sharing a name are one subscription, and the two apps compete for a single
	/// stream instead of each getting a copy.** Nothing downstream would have
	/// reported that; both apps would simply have received roughly half their
	/// events, intermittently.
	///
	/// Unknown type references are reported but are *not* errors: a subscription
	/// may legitimately be authored before the type it wants exists, and refusing
	/// the whole catalog for that would make the ordering of two edits matter.
	@JsonIgnore
	public List<String> validate() {
		List<String> findings = new ArrayList<>();

		List<String> seenTypes = new ArrayList<>();
		for (EventType declared : typesOrEmpty()) {
			String type = declared.getType();
			if (type == null || type.isEmpty()) {
				findings.add("An event type declaration has no type name.");
				continue;
			}
			if (!EventSubscription.isLegalName(type)) {
				findings.add("Event type '" + type
						+ "' contains characters that are not legal in a JMS selector literal or a Java identifier. "
						+ "Use letters, digits, dot, underscore and hyphen only.");
			}
			if (seenTypes.contains(type)) {
				findings.add("Event type '" + type + "' is declared more than once.");
			}
			seenTypes.add(type);
		}

		List<String> seenNames = new ArrayList<>();
		for (EventSubscription subscription : subscriptionsOrEmpty()) {
			String name = subscription.getName();
			if (name == null || name.isEmpty()) {
				findings.add("A subscription has no name. The name is its JMS client id and durable "
						+ "subscription name, so it cannot be blank.");
				continue;
			}
			if (!EventSubscription.isLegalName(name)) {
				findings.add("Subscription '" + name + "' contains characters that are not legal in a JMS client id "
						+ "or a Java class name. Use letters, digits, dot, underscore and hyphen only.");
			}
			if (seenNames.contains(name)) {
				findings.add("Subscription '" + name + "' is declared more than once. Two subscriptions sharing a "
						+ "name are one subscription: the apps would compete for a single stream rather than each "
						+ "receiving a copy.");
			}
			seenNames.add(name);

			String destination = null;
			for (String type : subscription.typesOrEmpty()) {
				EventType declared = findType(type);
				if (declared == null) {
					findings.add("Subscription '" + name + "' wants event type '" + type
							+ "', which this catalog does not declare. It will receive nothing for that type until "
							+ "the type is added.");
					continue;
				}
				String jndi = destinationFor(declared);
				if (destination == null) {
					destination = jndi;
				} else if (!destination.equals(jndi)) {
					findings.add("Subscription '" + name + "' spans two destinations (" + destination + " and " + jndi
							+ "). One consumer binds to one destination, so this cannot be generated — split it "
							+ "into a subscription per destination.");
					break;
				}
			}
		}

		return findings;
	}

	@JsonPropertyDescription("CloudEvents 'source' stamped on inbound events that arrive without one. Identifies this bus ingress as the origin.")
	public String getDefaultSource() {
		return defaultSource;
	}

	public void setDefaultSource(String defaultSource) {
		this.defaultSource = (defaultSource == null || defaultSource.isEmpty()) ? "/blade/events" : defaultSource;
	}

	@JsonPropertyDescription("JNDI name of the JMS connection factory the bus publishes through.")
	public String getConnectionFactoryJndi() {
		return connectionFactoryJndi;
	}

	public void setConnectionFactoryJndi(String connectionFactoryJndi) {
		this.connectionFactoryJndi = connectionFactoryJndi;
	}

	@JsonPropertyDescription("JNDI name of the destination used by event types that do not name one of their own.")
	public String getDefaultDestinationJndi() {
		return defaultDestinationJndi;
	}

	public void setDefaultDestinationJndi(String defaultDestinationJndi) {
		this.defaultDestinationJndi = defaultDestinationJndi;
	}

	@JsonPropertyDescription("What the ingress does when a payload fails its type's schema: OFF publishes without checking, WARN logs the failing field and publishes anyway, REJECT returns 400 and does not publish. Run in WARN while proving a new schema against live traffic.")
	public ValidationPolicy getValidation() {
		return validation;
	}

	public void setValidation(ValidationPolicy validation) {
		this.validation = (validation == null) ? ValidationPolicy.OFF : validation;
	}

	@JsonPropertyDescription("Refuse events whose type is not declared in this catalog. Leave off while producers are still being onboarded; turn on to make the catalog authoritative.")
	public boolean isRejectUnknownTypes() {
		return rejectUnknownTypes;
	}

	public void setRejectUnknownTypes(boolean rejectUnknownTypes) {
		this.rejectUnknownTypes = rejectUnknownTypes;
	}

	@JsonPropertyDescription("The declared event types.")
	public List<EventType> getTypes() {
		return types;
	}

	public void setTypes(List<EventType> types) {
		this.types = types;
	}

	/// The declared types, never null.
	@JsonIgnore
	public List<EventType> typesOrEmpty() {
		return (types == null) ? new ArrayList<>() : types;
	}

	@JsonPropertyDescription("Who consumes from this bus. One entry per subscriber, never one per event — several apps may want the same event, and each needs its own subscription identity to receive its own copy.")
	public List<EventSubscription> getSubscriptions() {
		return subscriptions;
	}

	public void setSubscriptions(List<EventSubscription> subscriptions) {
		this.subscriptions = subscriptions;
	}

	/// The declared subscriptions, never null.
	@JsonIgnore
	public List<EventSubscription> subscriptionsOrEmpty() {
		return (subscriptions == null) ? new ArrayList<>() : subscriptions;
	}
}
