/// The BLADE v3 event bus — a JMS pub/sub topic carrying CloudEvents 1.0
/// envelopes from any producer to any number of consuming apps.
///
/// This is the framework-side core: the [org.vorpal.blade.framework.v3.events.CloudEvent]
/// envelope, the [org.vorpal.blade.framework.v3.events.EventPublisher] that puts
/// it on the topic as a JSON `TextMessage`, and the
/// [org.vorpal.blade.framework.v3.events.EventBus] facade that owns the canonical
/// JNDI names and the node-local publisher reference.
///
/// It is the pub/sub sibling of the analytics queue
/// ([org.vorpal.blade.framework.v2.analytics]): same JMS plumbing discipline
/// (shared connection, per-thread session), but a Topic instead of a Queue and a
/// language-neutral JSON body instead of a Java-serialized `ObjectMessage`,
/// because the bus exists for *other apps* — potentially non-Java — to consume.
///
/// The deployable that fronts this (the HTTP→JMS ingress that lets a non-Java
/// producer like the Gumball attendant publish, plus a reference consumer) lives
/// in the `proto/events` app.
package org.vorpal.blade.framework.v3.events;
