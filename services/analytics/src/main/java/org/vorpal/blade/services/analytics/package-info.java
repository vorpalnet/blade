/// The BLADE analytics service: **one subscriber** on the BLADE event bus, whose
/// job is to write what happens on a call into a relational database.
///
/// ## Key Components
///
/// - **Subscriber** — a message-driven bean holding the durable `analytics-db`
///   subscription. It takes everything on the bus and writes the event types the
///   catalog marks persisted, with backpressure that holds events rather than
///   losing them when the database is unavailable.
/// - **SIP servlet layer** — a B2BUA that observes call and session lifecycle
///   transitions, and owns this application's own configuration
///   (`healthCheckInterval`, `healthCheckSql`, `domainId`)
/// - **Model** — the JPA entities. They live here, with the service that owns
///   the schema, rather than in the framework jar where they used to ship inside
///   every BLADE WAR.
///
/// ## Architecture
///
/// 1. Every BLADE application publishes each analytics fact as a **CloudEvent**
///    on the shared event bus. It states that something happened; it does not
///    know or care who acts on it.
/// 2. [AnalyticsEventListener][org.vorpal.blade.services.analytics.jms.AnalyticsEventListener]
///    subscribes and records. Another application may subscribe to exactly the
///    same events and act on them — a transfer app performing a transfer while
///    this one logs it — with neither aware of the other, because a topic gives
///    every subscriber its own copy.
/// 3. The surrogate keys are resolved here, from natural keys the wire carries:
///    an application instance from `(name, domain, server, appStartedAt)`, a call
///    from `(cluster_name, vorpal_id, created)`. The producer has no database and
///    no longer invents keys for one.
/// 4. When the database is unavailable, JMS delivery is suspended through JMX and
///    a health-check timer tests connectivity before resuming. A durable
///    subscription means the events wait rather than vanish.
///
/// **This is no longer a private pipeline.** It used to be: the producer filled
/// in JPA entities, Java-serialized them onto a queue of this service's own, and
/// the consumer discriminated by `instanceof` — which made every consumer
/// necessarily a database and necessarily on BLADE's classpath. Analytics is now
/// one subscriber among however many an operator declares, on the same bus, in
/// the same format, selected the same way.
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.services.analytics.jms]
/// The subscription and everything it needs: the MDB, the natural-key resolvers,
/// the live catalog read for `persist` flags, and the pure payload reader. See
/// that package's own documentation for why this subscriber deliberately has no
/// message selector, and for the millisecond-precision requirement the natural
/// keys depend on.
///
/// ### [org.vorpal.blade.services.analytics.sip]
/// Provides the SIP-facing components of the analytics service. The
/// [AnalyticsSipServlet][org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet] operates as a B2BUA that captures call lifecycle
/// transitions and session lifecycle events. [AnalyticsConfig][org.vorpal.blade.services.analytics.sip.AnalyticsConfig] defines this
/// service's own settings — the database health-check
/// (`healthCheckInterval`, `healthCheckSql`) and the `domainId` stamped as
/// `cluster_name` so a shared database can tell environments apart — and
/// [AnalyticsConfigSample][org.vorpal.blade.services.analytics.sip.AnalyticsConfigSample] provides default configuration with a 60-second
/// interval and `"SELECT 1"` query.
///
/// @see [org.vorpal.blade.services.analytics.jms.AnalyticsEventListener]
/// @see [org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet]
/// @see [org.vorpal.blade.services.analytics.sip.AnalyticsConfig]
package org.vorpal.blade.services.analytics;
