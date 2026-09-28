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

	/// Where the bus's JNDI names live, when not in this server's own tree.
	/// Null for the usual case: an engine, whose cluster hosts the bus.
	///
	/// **Why an application on the AdminServer needs it.** The bus's topic is
	/// a uniform distributed topic whose members live on the engine cluster's
	/// JMS server. The AdminServer is not in that cluster, so the topic is not
	/// bound in its JNDI tree and it cannot see the members even by name ("No
	/// stable LoadBalancer"). Looking the factory and the topic up through a
	/// context on the cluster gives an AdminServer application the same bus an
	/// engine has.
	///
	/// The effective value: [#configuredProviderUrl] when an application sets
	/// `events.providerUrl`, else what [#locate] found.
	private static volatile String providerUrl;

	/// The application's `events.providerUrl`, which always wins.
	private static volatile String configuredProviderUrl;

	/// What [#locate] found: the servers hosting the bus, when this one does not.
	private static volatile String locatedProviderUrl;

	/// When [#locate] last asked the domain, so a failing search is repeated
	/// at most once a [#LOCATE_INTERVAL_MS].
	private static volatile long locatedAt;

	/// How often a search that found nothing is repeated.
	static final long LOCATE_INTERVAL_MS = 60_000L;

	private EventBus() {
	}

	/// Set the application's configured provider URL; null or empty to let
	/// [#locate] decide. A change to the effective provider closes every
	/// publisher, so the next reconcile opens them against the new one;
	/// subscribers follow on their next reconcile.
	///
	/// @param url a WebLogic provider URL such as `t3://engine0:8001`, several
	///            comma-separated
	public static void setProviderUrl(String url) {
		configuredProviderUrl = (url == null || url.trim().isEmpty()) ? null : url.trim();
		apply();
	}

	/// Find the bus when this server's own naming directory does not have it,
	/// unless the application configured where it is.
	///
	/// On an engine the topic resolves locally and this costs that one lookup.
	/// On the AdminServer it does not, and [BusLocator] asks the domain for the
	/// running servers that host it. Called on every reconcile, and by a
	/// subscriber that cannot find the topic; a search that finds nothing is
	/// repeated at most once a [#LOCATE_INTERVAL_MS], so an engine that starts
	/// later is found without a redeploy.
	///
	/// @param destinationJndi the topic's JNDI name
	static void locate(String destinationJndi) {
		if (configuredProviderUrl != null) {
			return;
		}
		String destination = destinationOr(destinationJndi);
		try {
			javax.naming.InitialContext local = new javax.naming.InitialContext();
			try {
				local.lookup(destination);
			} finally {
				local.close();
			}
			locatedProviderUrl = null;
			apply();
			return;
		} catch (javax.naming.NamingException notHere) {
			// Look elsewhere below.
		}
		long now = System.currentTimeMillis();
		if (locatedProviderUrl == null && now - locatedAt < LOCATE_INTERVAL_MS) {
			return;
		}
		locatedAt = now;
		String found = BusLocator.discover(destination);
		if (found != null && !equal(found, locatedProviderUrl)) {
			log(java.util.logging.Level.INFO, "event bus: " + destination + " is hosted on " + found);
		}
		if (found != null || locatedProviderUrl == null) {
			// Keep the last list that worked through a search that found
			// nothing: its servers may be restarting, and WebLogic fails over
			// among them on its own.
			locatedProviderUrl = found;
		}
		apply();
	}

	/// Recompute the effective provider; on a change, close every publisher.
	private static void apply() {
		String wanted = (configuredProviderUrl != null) ? configuredProviderUrl : locatedProviderUrl;
		if (equal(wanted, providerUrl)) {
			return;
		}
		providerUrl = wanted;
		for (String destination : registeredDestinations()) {
			unregister(destination);
		}
	}

	/// The provider the bus's names are looked up through, or null for this
	/// server's own JNDI tree.
	public static String getProviderUrl() {
		return providerUrl;
	}

	/// A naming context for the bus's JNDI names: this server's own, or one on
	/// [#getProviderUrl].
	static javax.naming.InitialContext context() throws javax.naming.NamingException {
		java.util.Hashtable<String, String> env = environment();
		return (env == null) ? new javax.naming.InitialContext() : new javax.naming.InitialContext(env);
	}

	/// The JNDI environment for [#getProviderUrl], or null for this server's
	/// own tree. Shared by the lookups and the distributed-member watcher, which
	/// must look in the same place or it watches a topic that is not there.
	static java.util.Hashtable<String, String> environment() {
		String url = providerUrl;
		if (url == null) {
			return null;
		}
		java.util.Hashtable<String, String> env = new java.util.Hashtable<>();
		env.put(javax.naming.Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
		env.put(javax.naming.Context.PROVIDER_URL, url);
		return env;
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

	/// Where this node's publisher stands after [#reconcile].
	public enum Outcome {
		/// A publisher is installed; events leave this node.
		PUBLISHING,
		/// The configuration says `"enabled": false`.
		OFF,
		/// Nothing was asked for and the bus is not there: its factory or topic
		/// does not resolve. The quiet default of a domain that has not
		/// provisioned JMS, or an AdminServer application with no `providerUrl`.
		NOT_PROVISIONED,
		/// `"enabled": true` and the bus cannot be reached, or the bus is
		/// provisioned and failing. Either way, an error.
		FAILED
	}

	/// Null until the first [#reconcile], so the first outcome, whatever it
	/// is, gets its line in the log.
	private static volatile Outcome outcome;

	/// The last [#reconcile] outcome on this node; null before the first.
	public static Outcome outcome() {
		return outcome;
	}

	/// Whether nothing on this node is expected to publish: the bus is off, not
	/// provisioned, or not yet decided. Callers stay silent rather than report a
	/// miss nobody asked to avoid.
	public static boolean isQuiet() {
		Outcome now = outcome;
		return now == null || now == Outcome.OFF || now == Outcome.NOT_PROVISIONED;
	}

	/// Bring this node's publisher in line with the application's `events`
	/// settings. The one place the switch is read.
	///
	/// **Three settings.** `true` publishes and says so loudly if it cannot.
	/// `false` publishes nothing. Unset (the default) publishes when the bus is
	/// there and stays silent when it is not: a domain that never provisioned
	/// JMS gets no events and no errors, and one that provisions it later starts
	/// publishing at the next reload. An application with `analytics.enabled`
	/// set counts as asking for the bus, since that is what the flag used to do.
	///
	/// Logged once per change, not once per reload.
	///
	/// @param settings        the application's `events` block; null is unset
	/// @param analyticsAsked  whether `analytics.enabled` is true
	/// @return where the publisher now stands
	public static Outcome reconcile(EventBusSettings settings, boolean analyticsAsked) {
		EventBusSettings s = (settings == null) ? new EventBusSettings() : settings;
		Boolean enabled = s.isEnabled();
		boolean asked = Boolean.TRUE.equals(enabled) || (enabled == null && analyticsAsked);
		Outcome next;
		String detail = null;
		setProviderUrl(s.getProviderUrl());
		if (!Boolean.FALSE.equals(enabled)) {
			locate(s.getDestinationJndi());
		}
		if (Boolean.FALSE.equals(enabled)) {
			unregister(destinationOr(s.getDestinationJndi()));
			next = Outcome.OFF;
		} else {
			try {
				reconcilePublisher(true, s.getConnectionFactoryJndi(), s.getDestinationJndi());
				next = Outcome.PUBLISHING;
			} catch (Exception e) {
				unregister(destinationOr(s.getDestinationJndi()));
				// A name that does not resolve is a bus nobody provisioned. Anything
				// else is a bus that exists and is failing, which is worth saying
				// whether or not anybody asked for it.
				boolean missing = e instanceof javax.naming.NamingException;
				next = (asked || !missing) ? Outcome.FAILED : Outcome.NOT_PROVISIONED;
				detail = e.getClass().getSimpleName() + ": " + e.getMessage();
			}
		}
		Outcome previous = outcome;
		outcome = next;
		if (next != previous || next == Outcome.FAILED) {
			String where = s.getDestinationJndi() + (providerUrl == null ? "" : " via " + providerUrl);
			switch (next) {
			case PUBLISHING:
				log(java.util.logging.Level.INFO, "event bus: publishing to " + where);
				break;
			case OFF:
				log(java.util.logging.Level.INFO, "event bus: off in this application's configuration");
				break;
			case NOT_PROVISIONED:
				log(java.util.logging.Level.INFO, "event bus: not provisioned (" + where
						+ " does not resolve); this application will not publish");
				break;
			default:
				log(java.util.logging.Level.SEVERE, "event bus: \"enabled\" is true but " + where
						+ " cannot be reached; nothing this application produces is published: " + detail);
			}
		}
		return next;
	}

	private static String destinationOr(String destinationJndi) {
		return (destinationJndi == null || destinationJndi.isEmpty()) ? TOPIC_JNDI : destinationJndi;
	}

	/// The application's SIP logger when it has one; an application with no
	/// SIP servlet, or one reconciling before its logger exists, uses the
	/// server log.
	private static void log(java.util.logging.Level level, String message) {
		org.vorpal.blade.framework.v2.logging.Logger sip =
				org.vorpal.blade.framework.v2.config.SettingsManager.getSipLogger();
		if (sip == null) {
			java.util.logging.Logger.getLogger(EventBus.class.getName()).log(level, message);
		} else if (level == java.util.logging.Level.SEVERE) {
			sip.severe(message);
		} else {
			sip.info(message);
		}
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
	private static final ConcurrentMap<String, SubscriberMeters> SUBSCRIBER_METERS = new ConcurrentHashMap<>();

	/// One subscription's three counters. Any of them may be null.
	static final class SubscriberMeters {
		final org.vorpal.blade.framework.v3.metrics.Counter.Series received;
		final org.vorpal.blade.framework.v3.metrics.Counter.Series handled;
		final org.vorpal.blade.framework.v3.metrics.Counter.Series failed;

		SubscriberMeters(org.vorpal.blade.framework.v3.metrics.Counter.Series received,
				org.vorpal.blade.framework.v3.metrics.Counter.Series handled,
				org.vorpal.blade.framework.v3.metrics.Counter.Series failed) {
			this.received = received;
			this.handled = handled;
			this.failed = failed;
		}
	}

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
			SUBSCRIBER_METERS.put(subscriptionName, new SubscriberMeters(received, handled, failed));
		}
	}

	/// This subscription's meters, or null when nobody is counting. Package
	/// private: [EventBusControl] reports them, nothing else needs them.
	static SubscriberMeters metersFor(String subscriptionName) {
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
				&& equal(installed.getProviderUrl(), providerUrl)
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
		SubscriberMeters meters = SUBSCRIBER_METERS.get(subscriptionName);
		if (meters != null) {
			subscriber.meter(meters.received, meters.handled, meters.failed);
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
	/// Application code publishes through [Events], which never throws and
	/// reports a drop. This is for a producer that must see the failure.
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
