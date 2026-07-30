package org.vorpal.blade.framework.v3.events;

/// Whether an event type is carried on a topic or a queue.
///
/// The distinction is not cosmetic — it decides how many consumers get the
/// event, and it drives the generated MDB's `destinationType` activation
/// property. Choosing wrong is the kind of mistake that shows up as "only one
/// of our two apps sees the event," which is why the catalog records it once and
/// everything downstream derives from it.
public enum DestinationKind {

	/// Pub/sub fan-out: every subscribing app receives its own copy. The default,
	/// and the right choice for "other apps consume this" — an open, growing set
	/// of consumers. A consumer that must act exactly once uses a durable
	/// subscription and dedupes on the CloudEvent `id`.
	TOPIC,

	/// Point-to-point: exactly one consumer receives each message, and competing
	/// consumers share the load. The right choice for work handoff, where a
	/// second copy would mean the work is done twice.
	QUEUE;

	/// The `destinationType` activation-config value the generated MDB needs.
	public String activationDestinationType() {
		return (this == QUEUE) ? "javax.jms.Queue" : "javax.jms.Topic";
	}
}
