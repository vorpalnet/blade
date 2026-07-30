/// The BLADE v3 event bus — JMS destinations carrying CloudEvents 1.0 envelopes
/// from any producer to any number of consuming apps, and the catalog that says
/// what those events are.
///
/// **The wire.** [org.vorpal.blade.framework.v3.events.CloudEvent] is the
/// envelope, [org.vorpal.blade.framework.v3.events.EventPublisher] puts it on a
/// destination as a JSON `TextMessage`, and
/// [org.vorpal.blade.framework.v3.events.EventBus] owns the canonical JNDI names
/// and the node-local publishers. This is the pub/sub sibling of the analytics
/// queue ([org.vorpal.blade.framework.v2.analytics]) — same JMS plumbing
/// discipline, but a Topic by default instead of a Queue, and a
/// language-neutral JSON body instead of a Java-serialized `ObjectMessage`,
/// because the bus exists for *other apps* — potentially non-Java — to consume.
///
/// **The catalog.** [org.vorpal.blade.framework.v3.events.EventCatalog] holds an
/// [org.vorpal.blade.framework.v3.events.EventType] per event, each describing
/// its payload as [org.vorpal.blade.framework.v3.events.EventField]s and binding
/// it to a destination. That one declaration is the source of truth: an event
/// used to be four hand-written things that nothing checked against each other —
/// the destination provisioned by a WLST script, the publisher's JNDI constant,
/// a consumer MDB with a hand-typed message selector, and a payload shape that
/// existed nowhere at all. A typo in the selector was a silent no-op.
///
/// **The generator.**
/// [org.vorpal.blade.framework.v3.events.EventSourceGenerator] turns a
/// declaration into the payload class, its JSON Schema, a consumer MDB whose
/// selector is derived rather than typed, a producer snippet, a sample envelope,
/// and a downloadable Maven module. Every method is pure, so the whole thing is
/// exercisable in a plain JVM — see `EventSourceGeneratorSmokeTest`.
///
/// The deployables that front this are the `services/events` runtime (the
/// HTTP→JMS ingress that lets a non-Java producer such as the Gumball attendant
/// publish) and the `admin/events-console` designer and JMS administration
/// console.
package org.vorpal.blade.framework.v3.events;
