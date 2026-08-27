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

	/// JNDI name of the destination the broker moves a message to when a
	/// consumer will not accept it.
	///
	/// A message lands here after exhausting the topic's redelivery limit — a
	/// payload nobody can parse, or an event that fails every time it is
	/// applied. **Anything on this queue is an event that was not processed**,
	/// which makes a non-zero depth one of the few unambiguous "something is
	/// wrong" signals this system produces. It is reported by
	/// [EventBusControl#getStatus].
	///
	/// Provisioned by `services/events/notes/configure-messaging-jms.py`, where
	/// this string used to live alone.
	public static final String ERROR_QUEUE_JNDI = "jms/BladeEventBusErrorQueue";

	/// Publishers by destination JNDI name, installed at web-app startup. Empty
	/// until the events service has initialized — or if its JMS resources are
	/// missing, in which case publishing is a no-op rather than an error.
	private static final ConcurrentMap<String, EventPublisher> PUBLISHERS = new ConcurrentHashMap<>();

	/// Subscribers by subscription name, installed at web-app startup and
	/// re-established on config reload.
	///
	/// Keyed by the SUBSCRIBER's name rather than the destination, because
	/// several subscriptions legitimately share one destination — that is what a
	/// topic is for — whereas two subscriptions sharing a name are one
	/// subscription named twice, with the two apps splitting a single stream
	/// instead of each getting a copy.
	private static final ConcurrentMap<String, EventSubscriber> SUBSCRIBERS = new ConcurrentHashMap<>();

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

	/// Close every installed publisher and subscriber. Called at web-app
	/// shutdown.
	///
	/// Subscribers are closed, not unsubscribed: a redeploy must leave the
	/// durable subscription in place so the broker holds events until the app
	/// comes back.
	public static void unregisterAll() {
		for (EventPublisher publisher : PUBLISHERS.values()) {
			publisher.close();
		}
		PUBLISHERS.clear();
		for (EventSubscriber subscriber : SUBSCRIBERS.values()) {
			subscriber.close();
		}
		SUBSCRIBERS.clear();
		defaultDestinationJndi = TOPIC_JNDI;
	}

	/// Meters applied to any publisher this class creates. Set once by the
	/// application at startup so a publisher rebuilt on a later config reload
	/// keeps being counted — otherwise the counters silently stop at the first
	/// reload, which is exactly the kind of quiet gap this whole area had too
	/// much of.
	private static volatile org.vorpal.blade.framework.v3.metrics.Counter.Series publishedMeter;
	private static volatile org.vorpal.blade.framework.v3.metrics.Counter.Series failedMeter;

	/// Remember the meters to attach to publishers, now and after any rebuild.
	public static void setPublisherMeters(org.vorpal.blade.framework.v3.metrics.Counter.Series published,
			org.vorpal.blade.framework.v3.metrics.Counter.Series failed) {
		publishedMeter = published;
		failedMeter = failed;
	}

	/// Bring this node's default publisher into line with the configuration.
	///
	/// **Why this is a reconcile rather than a one-shot.** An application's
	/// publisher used to be built once, during servlet initialization, and was
	/// never revisited. Turning the bus on in an application's config therefore
	/// did nothing until somebody redeployed the application — the config said
	/// enabled, the log said nothing, and [#publish(CloudEvent)] went on being a
	/// silent no-op because no publisher was ever registered. That is a
	/// genuinely hard failure to see, and it is the reason this exists.
	///
	/// Diff, not rebuild: an unchanged destination keeps its connection, so
	/// editing an unrelated part of the config does not disturb publishing.
	///
	/// @param enabled         whether this application should publish at all
	/// @param connectionFactoryJndi the factory to publish through
	/// @param destinationJndi the destination to publish to
	/// @return true if a publisher was created or removed
	/// @throws NamingException if a JNDI lookup fails
	/// @throws JMSException    if the publisher cannot be created
	public static boolean reconcilePublisher(boolean enabled, String connectionFactoryJndi, String destinationJndi)
			throws javax.naming.NamingException, javax.jms.JMSException {

		String destination = (destinationJndi == null || destinationJndi.isEmpty())
				? TOPIC_JNDI : destinationJndi;

		if (!enabled) {
			// Turning the bus off has to actually close the connection, or
			// "disabled" means "still publishing until the next restart".
			return unregister(destination);
		}

		if (PUBLISHERS.containsKey(destination)) {
			setDefaultDestinationJndi(destination);
			return false;
		}

		EventPublisher publisher = new EventPublisher(connectionFactoryJndi, destination);
		publisher.init();
		publisher.meter(publishedMeter, failedMeter);
		PUBLISHERS.put(destination, publisher);
		setDefaultDestinationJndi(destination);
		return true;
	}

	/// Meters to attach to each subscription's subscriber, by subscription
	/// name.
	///
	/// Held here rather than on the subscriber for the same reason the
	/// publisher meters are: [#reconcileSubscriber] replaces the subscriber
	/// whenever the selector changes, and meters attached to the instance would
	/// stop counting at the first catalog edit — silently, which is the failure
	/// mode this whole area had too much of.
	private static final ConcurrentMap<String, org.vorpal.blade.framework.v3.metrics.Counter.Series[]>
			SUBSCRIBER_METERS = new ConcurrentHashMap<>();

	/// Remember the meters for one subscription, now and after any rebuild.
	///
	/// @param received incremented per message taken off the destination
	/// @param handled  incremented per message in a committed batch
	/// @param failed   incremented per message in a rolled-back batch
	public static void setSubscriberMeters(String subscriptionName,
			org.vorpal.blade.framework.v3.metrics.Counter.Series received,
			org.vorpal.blade.framework.v3.metrics.Counter.Series handled,
			org.vorpal.blade.framework.v3.metrics.Counter.Series failed) {
		if (subscriptionName != null) {
			SUBSCRIBER_METERS.put(subscriptionName,
					new org.vorpal.blade.framework.v3.metrics.Counter.Series[] { received, handled, failed });
		}
	}

	/// This subscription's meters, or null when nobody is counting. Package
	/// private: [EventBusControl] reports them, nothing else needs them.
	static org.vorpal.blade.framework.v3.metrics.Counter.Series[] metersFor(String subscriptionName) {
		return SUBSCRIBER_METERS.get(subscriptionName);
	}

	/// Install a subscriber, keyed by its subscription name.
	///
	/// @param subscriber an initialized subscriber
	public static void register(EventSubscriber subscriber) {
		if (subscriber != null && subscriber.getSubscriptionName() != null) {
			SUBSCRIBERS.put(subscriber.getSubscriptionName(), subscriber);
		}
	}

	/// The subscriptions currently consuming on this node.
	///
	/// Diffed against the catalog on every config reload, so an operator can
	/// change what a consumer listens to — or stop it listening at all —
	/// without a redeploy. That was impossible while the selector was an
	/// annotation constant.
	public static java.util.Set<String> registeredSubscriptions() {
		return new java.util.HashSet<>(SUBSCRIBERS.keySet());
	}

	/// The subscriber for a subscription name, or null when none is installed.
	public static EventSubscriber subscriberFor(String subscriptionName) {
		return SUBSCRIBERS.get(subscriptionName);
	}

	/// Bring one subscription into line with what the configuration now asks
	/// for, rebuilding it only if something that matters actually changed.
	///
	/// **This is the call that makes a consumer reconfigurable.** An app puts
	/// it in its `SettingsManager.initialize()` hook, which runs on every
	/// config reload, and the subscription follows the catalog from then on —
	/// widen the selector and the running consumer starts receiving more;
	/// narrow it and it stops.
	///
	/// Diff, not rebuild, for the same reason the publisher side diffs: tearing
	/// down a subscription that did not change would drop the consumer's
	/// connection and re-deliver whatever was in flight, on every unrelated
	/// edit to an unrelated part of the config.
	///
	/// @return true if the subscription was created or rebuilt, false if the
	///         installed one already matched
	/// @throws NamingException if a JNDI lookup fails
	/// @throws JMSException    if the subscription cannot be established
	public static boolean reconcileSubscriber(String subscriptionName, String connectionFactoryJndi,
			String destinationJndi, String selector, boolean durable, EventSubscriber.Handler handler)
			throws javax.naming.NamingException, javax.jms.JMSException {
		return reconcileSubscriber(subscriptionName, connectionFactoryJndi, destinationJndi, selector, durable,
				handler, EventSubscriber.DEFAULT_BATCH_SIZE, EventSubscriber.DEFAULT_BATCH_MILLIS);
	}

	/// @param batchSize   events per transaction
	/// @param batchMillis how long a partial batch waits before committing
	/// @see #reconcileSubscriber(String, String, String, String, boolean, EventSubscriber.Handler)
	public static boolean reconcileSubscriber(String subscriptionName, String connectionFactoryJndi,
			String destinationJndi, String selector, boolean durable, EventSubscriber.Handler handler,
			int batchSize, long batchMillis)
			throws javax.naming.NamingException, javax.jms.JMSException {

		String wanted = (selector == null || selector.isEmpty()) ? null : selector;
		EventSubscriber installed = SUBSCRIBERS.get(subscriptionName);

		if (installed != null
				&& equal(installed.getDestinationJndi(), destinationJndi)
				&& equal(installed.getSelector(), wanted)
				&& installed.isDurable() == durable) {
			return false;
		}

		if (installed != null) {
			// Close rather than unsubscribe: the broker keeps holding events
			// for this subscription name during the moment it takes to
			// re-establish, so a selector change does not lose the backlog.
			SUBSCRIBERS.remove(subscriptionName);
			installed.close();
		}

		EventSubscriber subscriber = new EventSubscriber(connectionFactoryJndi, destinationJndi, subscriptionName,
				wanted, durable, handler, batchSize, batchMillis);
		org.vorpal.blade.framework.v3.metrics.Counter.Series[] meters = SUBSCRIBER_METERS.get(subscriptionName);
		if (meters != null) {
			subscriber.meter(meters[0], meters[1], meters[2]);
		}
		subscriber.init();
		SUBSCRIBERS.put(subscriptionName, subscriber);
		return true;
	}

	private static boolean equal(String a, String b) {
		return (a == null) ? (b == null) : a.equals(b);
	}

	/// Stop and close one subscription, leaving it registered with the broker
	/// so events accumulate while it is away.
	///
	/// @param subscriptionName the subscription to stop consuming
	/// @return true if a subscriber was installed and has now been closed
	public static boolean unregisterSubscriber(String subscriptionName) {
		EventSubscriber removed = SUBSCRIBERS.remove(subscriptionName);
		if (removed == null) {
			return false;
		}
		removed.close();
		return true;
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
