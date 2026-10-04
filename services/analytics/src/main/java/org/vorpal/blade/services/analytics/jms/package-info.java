/// The analytics database's subscription on the BLADE event bus: it receives
/// CloudEvents, decides which ones the catalog says to keep, and writes them
/// through JPA — with backpressure that holds events rather than losing them
/// when the database is unavailable.
///
/// ## Key Components
///
/// - [AnalyticsSubscription] — owns the durable topic subscription named
///   `analytics-db` and rebuilds its selector whenever the catalog changes
/// - [AnalyticsEventListener] — the handler: writes each batch
/// - [AnalyticsCatalog] — the live event catalog, read for each type's `persist`
///   flag
/// - [ApplicationResolver] / [SessionResolver] — the two foreign keys every row
///   needs, computed from the natural keys on the wire rather than looked up.
///   See `framework v3.analytics.NaturalKey`: no key in this schema is assigned by the
///   database, so resolving is a primary-key `find` and creating needs no
///   round trip to discover what id the row got.
/// - [Wire] — reading the payload; pure, and the only part of this package that
///   can be tested without a live domain
///
/// ## Architecture
///
/// SIP applications publish each analytics fact as a **CloudEvent** on the BLADE
/// event bus. Whoever wants them subscribes; this application is one such
/// subscriber, and its job is to record. Another application may subscribe to
/// exactly the same events and act on them without either knowing the other
/// exists — a topic gives every subscriber its own copy.
///
/// **This replaced an ObjectMessage queue.** The producer used to fill in JPA
/// entities — primary keys, foreign keys and all — Java-serialize them, and put
/// them on a queue of its own; the consumer discriminated with `instanceof`.
/// That made every consumer necessarily a database, necessarily on BLADE's
/// classpath, and made selector-based routing impossible. Nothing of the old
/// path survives except the parts worth keeping, listed under resilience below.
///
/// ## How this subscriber selects
///
/// An *actor* (a transfer application acting on a refer) names the event types
/// it handles. A selector is derived from them, the broker filters, and the app
/// never wakes for an event it would ignore. It fails closed: a type nobody
/// listed is never enqueued for it.
///
/// A *sink* wants "everything marked persisted", which is a property of the
/// types, not of any subscription. Its selector has two halves: the types
/// [AnalyticsCatalog#persistedTypes] names (declared `persist: true`, plus the
/// framework's own unless a declaration turns one off), OR any event the
/// publisher marked as call-scoped (`eventCall`) whose type is not in
/// [AnalyticsCatalog#switchedOff]. The second half is why an application's
/// undeclared call event is still recorded.
///
/// The selector is rebuilt when the catalog changes, so a type marked
/// persisted this afternoon is recorded this afternoon, with no redeploy. An
/// undeclared operations event (no `vorpalId`) is not recorded until it is
/// declared. [AnalyticsEventListener] still checks each event against the
/// catalog and counts what it drops; with the broker filtering, that count
/// should stay near zero, and a rising one means the live selector and the
/// catalog disagree.
///
/// ## Resolving the keys
///
/// Every row needs two foreign keys the wire does not carry, because the
/// producer has no database and should not be inventing its keys:
///
/// - **`application_id`** — resolved from `(name, domain, server, appStartedAt)`,
///   which is exactly what an application instance *is*: one app, on one server,
///   with one configuration, where a restart is a new instance. This replaced a
///   producer-minted random 64-bit id with a stated ~1e-11 collision risk.
/// - **`session_id`** — resolved from `(cluster_name, vorpal_id, created)`. The
///   birth instant is part of the key because a Vorpal-ID is 32 bits and is only
///   checked for uniqueness among *live* sessions: ids are reused, so the id
///   alone is a correlator rather than an identity.
///
/// Both resolvers create the row if it is not there, so arrival order does not
/// matter — a session key that arrives before its session start creates the
/// session rather than being parked, dropped or orphaned.
///
/// **Millisecond precision is load-bearing.** Both natural keys include a
/// timestamp, the wire carries ISO-8601 instants with milliseconds, and a
/// `DATETIME` column without fractional seconds truncates on write. The lookup
/// would then compare an un-truncated value against a truncated column, miss
/// every time, and insert a duplicate for every event. The schema declares
/// `DATETIME(3)`; see the header of `MySQL-database-schema.sql`.
///
/// ## Redelivery
///
/// A durable subscription redelivers — on rolling restart, on failover, on
/// rollback — so it is routine rather than exceptional. `sessions`,
/// `session_keys` and `applications` are idempotent by natural key. `events` has
/// no natural key, so it carries the CloudEvent id in `event_uid` under a UNIQUE
/// constraint; without it a rolling restart would duplicate rows silently.
///
/// ## Database resilience
///
/// Carried over from the old consumer, unchanged in substance. When a database
/// *connection* error is detected — `isDatabaseConnectionError` walks the cause
/// chain for `SQLTransientConnectionException`, `ConnectException`,
/// `SocketException` and known message patterns — the listener:
///
/// 1. sets a volatile `databaseDown` flag, to discard the few messages already
///    in flight;
/// 2. suspends JMS delivery through WebLogic JMX
///    (`MessageDrivenEJBRuntime`, queried by
///    `com.bea:Type=MessageDrivenEJBRuntime,Name=AnalyticsEventListener,*`), so
///    events accumulate in the subscription's store instead of being lost;
/// 3. starts an EJB timer that tests the connection with a configurable query
///    (`healthCheckSql`);
/// 4. resumes delivery and cancels the timer when the database comes back.
///
/// A *data* error — bad payload, constraint violation — is logged and the
/// message consumed. Forcing redelivery of something that will never succeed
/// would spin that one event ahead of every event behind it.
///
/// Two things worth knowing about the suspend. The MBean query matches on this
/// class's **simple name**, so two MDBs sharing one would suspend each other.
/// And a long outage draws on the destination's quota, which is shared with
/// every other subscriber — a database down for a day can start failing
/// publishes for applications that have nothing to do with analytics.
///
/// @see [org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet]
/// @see [org.vorpal.blade.services.analytics.sip.AnalyticsConfig]
/// @see [org.vorpal.blade.framework.v3.events.BladeEventTypes]
package org.vorpal.blade.services.analytics.jms;
