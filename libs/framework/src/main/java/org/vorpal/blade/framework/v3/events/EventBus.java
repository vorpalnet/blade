package org.vorpal.blade.framework.v3.events;

/// The BLADE v3 event bus — a JMS pub/sub topic that carries [CloudEvent]s from
/// any producer to any number of consuming apps.
///
/// This class is the single source of truth for the bus's JNDI names. The
/// analytics queue scattered its two names across five places (the publisher
/// ctor, the MDB `mappedName`, the audit constants, and two provisioning
/// scripts); here the names are `public static final` constants referenced
/// everywhere — including the consumer MDB's `@MessageDriven(mappedName = ...)`,
/// which is legal precisely because a String constant is a compile-time
/// constant expression.
///
/// **Why a Topic, not a Queue.** The analytics destination is a
/// `UniformDistributedQueue` because it has exactly one consumer (the DB
/// writer): point-to-point, each message delivered once. The event bus exists
/// for *other apps to consume* — an open, growing set — which is pub/sub. It is
/// provisioned as a `UniformDistributedTopic` so every subscribing app receives
/// its own copy; a consumer that must act exactly once uses a durable
/// subscription and dedupes on the CloudEvent `id`.
///
/// The publisher reference is a per-node static, set by the owning web app's
/// startup listener — the same shape as `Analytics.jmsPublisher`. It is not a
/// clustered singleton: each engine node holds its own connection.
public final class EventBus {

	/// JNDI name of the topic connection factory (provisioned by
	/// `configure-events-jms.py`).
	public static final String CONNECTION_FACTORY_JNDI = "jms/BladeEventBusConnectionFactory";

	/// JNDI name of the uniform distributed topic the bus publishes to.
	public static final String TOPIC_JNDI = "jms/BladeEventBusTopic";

	/// The node-local publisher, installed at web-app startup. Null until the
	/// event-bus app has initialized (or if its JMS resources are missing).
	public static volatile EventPublisher publisher;

	private EventBus() {
	}

	/// Publish an event to the bus if a publisher is installed on this node.
	/// Silently no-ops when the bus is not available, so a producer is never
	/// coupled to bus liveness.
	///
	/// @param event the CloudEvent to publish
	/// @throws javax.jms.JMSException if the send fails
	/// @throws java.io.IOException    if the envelope cannot be serialized
	public static void publish(CloudEvent event) throws javax.jms.JMSException, java.io.IOException {
		EventPublisher p = publisher;
		if (p != null) {
			p.publish(event);
		}
	}

	/// True when a publisher is installed and ready on this node.
	public static boolean isReady() {
		return publisher != null;
	}
}
