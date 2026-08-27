package org.vorpal.blade.framework.v3.events;

/// The event bus's control plane: what it is doing, and whether it works.
///
/// **On the MBean server rather than behind an HTTP endpoint**, like every
/// other BLADE control surface. An operator asking "is the bus alive" is doing
/// operations, not consuming an API, and the answer should be reachable from
/// the same console and the same WLST session as everything else they are
/// looking at.
///
/// Both operations exist because of the same failure. A dead pipeline used to
/// be indistinguishable from a healthy one from the outside: publishing is a
/// deliberate no-op when no publisher is installed, consumers swallowed their
/// own failures, and every log line was either absent or reassuring. Startup
/// lines and counters closed most of that gap; these close the rest, because
/// counters only tell you what has already happened and an operator standing
/// in front of a quiet system needs to ask a question *now*.
public interface EventBusControlMXBean {

	/// What this node's bus is doing: destinations published to, subscriptions
	/// consuming, and how many consumers each one holds.
	///
	/// A subscription with **zero** consumers is the interesting line. It means
	/// the subscription exists but nothing is attached to a member of the
	/// distributed destination, which is what "connected but receiving nothing"
	/// looks like from the inside.
	String getStatus();

	/// Publish one synthetic event and wait for it to come back.
	///
	/// A genuine round trip — publisher, broker, and a temporary subscriber of
	/// its own — rather than a liveness check on any single piece. It answers
	/// the question an operator actually has, which is not "is the connection
	/// factory bound" but "if an application published something right now,
	/// would anything receive it".
	///
	/// Safe to run on a live system: the event carries a type of its own that
	/// no catalog declares, so no real consumer selects for it and the
	/// analytics sink will not record it. It is not free — it puts a message
	/// on the destination — so it is an operation, not something polled.
	///
	/// @return a line describing what happened, suitable for reading in a
	///         console
	String selfTest();
}
