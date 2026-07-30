package org.vorpal.blade.framework.v3.events;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// The BLADE v3 event bus — JMS destinations carrying [CloudEvent]s from any
/// producer to any number of consuming apps.
///
/// This class holds the canonical JNDI names and the node-local publishers. The
/// analytics queue scattered its two names across five places (the publisher
/// constructor, the MDB `mappedName`, the audit constants, and two provisioning
/// scripts); here they are `public static final` constants referenced everywhere
/// — including a consumer MDB's `@MessageDriven(mappedName = ...)`, which is
/// legal precisely because a String constant is a compile-time constant
/// expression.
///
/// **Why a Topic by default.** The analytics destination is a
/// `UniformDistributedQueue` because it has exactly one consumer (the DB
/// writer): point-to-point, each message delivered once. The event bus exists
/// for *other apps to consume* — an open, growing set — which is pub/sub. The
/// default destination is a `UniformDistributedTopic` so every subscribing app
/// receives its own copy; a consumer that must act exactly once uses a durable
/// subscription and dedupes on the CloudEvent `id`. An event type that wants
/// point-to-point handoff declares [DestinationKind#QUEUE] in the catalog and
/// gets its own destination.
///
/// **Several destinations, not one.** The catalog lets an event type name its
/// own destination, so this holds a publisher per destination JNDI name rather
/// than a single one. [#publish(CloudEvent)] still sends to the default, which
/// is what a producer that does not care should call.
///
/// **This is not a singleton in the sense the no-singletons rule means.** The
/// map is per-JVM state holding one JMS connection per destination for *this*
/// engine node. Nothing is shared across the cluster, no node coordinates with
/// another, and each node stands its publishers up independently at web-app
/// startup. Replacing it with per-request connections would mean opening a JMS
/// connection per published event at 1000+ CPS.
public final class EventBus {

	/// JNDI name of the connection factory the bus publishes through.
	public static final String CONNECTION_FACTORY_JNDI = "jms/BladeEventBusConnectionFactory";

	/// JNDI name of the default uniform distributed topic.
	public static final String TOPIC_JNDI = "jms/BladeEventBusTopic";

	/// Publishers by destination JNDI name, installed at web-app startup. Empty
	/// until the events service has initialized — or if its JMS resources are
	/// missing, in which case publishing is a no-op rather than an error.
	private static final ConcurrentMap<String, EventPublisher> PUBLISHERS = new ConcurrentHashMap<>();

	/// The destination [#publish(CloudEvent)] sends to when the caller does not
	/// name one. Set at startup from the catalog; falls back to [#TOPIC_JNDI].
	private static volatile String defaultDestinationJndi = TOPIC_JNDI;

	private EventBus() {
	}

	/// Install a publisher, keyed by the destination it sends to. Called once
	/// per destination at web-app startup.
	///
	/// @param publisher an initialized publisher
	public static void register(EventPublisher publisher) {
		if (publisher != null && publisher.getDestinationJndi() != null) {
			PUBLISHERS.put(publisher.getDestinationJndi(), publisher);
		}
	}

	/// The destinations a publisher is currently installed for.
	///
	/// The events service diffs this against the catalog on every config reload,
	/// so a destination added or removed through the console takes effect without
	/// a redeploy — and without tearing down publishers that did not change.
	public static java.util.Set<String> registeredDestinations() {
		return new java.util.HashSet<>(PUBLISHERS.keySet());
	}

	/// Remove and close the publisher for one destination.
	///
	/// @param destinationJndi the destination to stop publishing to
	/// @return true if a publisher was installed and has now been closed
	public static boolean unregister(String destinationJndi) {
		EventPublisher removed = PUBLISHERS.remove(destinationJndi);
		if (removed == null) {
			return false;
		}
		removed.close();
		return true;
	}

	/// Remove and close every installed publisher. Called at web-app shutdown.
	public static void unregisterAll() {
		for (EventPublisher publisher : PUBLISHERS.values()) {
			publisher.close();
		}
		PUBLISHERS.clear();
		defaultDestinationJndi = TOPIC_JNDI;
	}

	/// The publisher for a destination, or null when none is installed.
	///
	/// @param destinationJndi the destination JNDI name; null means the default
	public static EventPublisher publisherFor(String destinationJndi) {
		String key = (destinationJndi == null || destinationJndi.isEmpty()) ? defaultDestinationJndi : destinationJndi;
		return PUBLISHERS.get(key);
	}

	/// Set the destination used when a caller names none.
	public static void setDefaultDestinationJndi(String jndi) {
		defaultDestinationJndi = (jndi == null || jndi.isEmpty()) ? TOPIC_JNDI : jndi;
	}

	/// The destination used when a caller names none.
	public static String getDefaultDestinationJndi() {
		return defaultDestinationJndi;
	}

	/// Publish an event to the default destination if a publisher is installed
	/// on this node. Silently no-ops when the bus is not available, so a producer
	/// is never coupled to bus liveness.
	///
	/// @param event the CloudEvent to publish
	/// @throws javax.jms.JMSException if the send fails
	/// @throws java.io.IOException    if the envelope cannot be serialized
	public static void publish(CloudEvent event) throws javax.jms.JMSException, java.io.IOException {
		publish(event, null);
	}

	/// Publish an event to a named destination if a publisher is installed on
	/// this node.
	///
	/// @param event           the CloudEvent to publish
	/// @param destinationJndi the destination, or null for the default
	/// @throws javax.jms.JMSException if the send fails
	/// @throws java.io.IOException    if the envelope cannot be serialized
	public static void publish(CloudEvent event, String destinationJndi)
			throws javax.jms.JMSException, java.io.IOException {
		EventPublisher publisher = publisherFor(destinationJndi);
		if (publisher != null) {
			publisher.publish(event);
		}
	}

	/// True when a publisher is installed for the default destination on this
	/// node.
	public static boolean isReady() {
		return publisherFor(null) != null;
	}

	/// True when a publisher is installed for the given destination.
	public static boolean isReady(String destinationJndi) {
		return publisherFor(destinationJndi) != null;
	}
}
