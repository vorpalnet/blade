/// This package provides the root of the Vorpal Blade analytics service, a SIP analytics
/// pipeline that captures call lifecycle events and persists them to a relational database
/// through an asynchronous JMS-based architecture.
///
/// ## Key Components
///
/// The analytics service is composed of two cooperating sub-systems:
///
/// - **SIP Servlet Layer** -- A B2BUA servlet that observes call and session lifecycle
///   transitions (started, answered, connected, completed, declined, abandoned) and
///   publishes JPA analytics entities to a JMS distributed queue
/// - **JMS Consumer Layer** -- A message-driven bean that consumes analytics entities
///   from the queue and persists them to a database via JPA, with built-in resilience
///   for database outages including automatic suspend/resume of JMS delivery
///
/// ## Architecture
///
/// The service uses a decoupled, asynchronous pipeline:
///
/// 1. [AnalyticsSipServlet][org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet] intercepts SIP call lifecycle events as a B2BUA
/// 2. Analytics entities (Application, Session, Event) are published as JMS
///    `ObjectMessage` payloads to a WebLogic distributed queue
/// 3. [AnalyticsJmsListener][org.vorpal.blade.services.analytics.jms.AnalyticsJmsListener] MDB consumes these messages and merges them into
///    the database using JPA
/// 4. When the database is unavailable, JMS delivery is automatically suspended
///    and a health-check timer periodically tests connectivity before resuming
///
/// This design ensures that analytics data is captured without impacting SIP call
/// processing latency, and that transient database outages do not cause data loss
/// thanks to the persistent JMS store.
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.services.analytics.jms]
/// Provides the JMS message consumer for the analytics service. The
/// [AnalyticsJmsListener][org.vorpal.blade.services.analytics.jms.AnalyticsJmsListener] MDB receives JPA entity objects via JMS `ObjectMessage`
/// from the `jms/BladeAnalyticsDistributedQueue` and persists them using JPA with
/// bean-managed transactions. Includes database resilience logic that detects
/// connection errors (inspecting the cause chain for `SQLTransientConnectionException`,
/// `ConnectException`, `SocketException`), suspends JMS delivery via WebLogic JMX,
/// and starts a periodic EJB timer health check that resumes delivery when the
/// database recovers.
///
/// ### [org.vorpal.blade.services.analytics.sip]
/// Provides the SIP-facing components of the analytics service. The
/// [AnalyticsSipServlet][org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet] operates as a B2BUA that captures call lifecycle
/// transitions and session lifecycle events, logging them and publishing analytics
/// entities to the JMS queue. [AnalyticsConfig][org.vorpal.blade.services.analytics.sip.AnalyticsConfig] defines database health-check
/// settings (`healthCheckInterval` and `healthCheckSql`), and
/// [AnalyticsConfigSample][org.vorpal.blade.services.analytics.sip.AnalyticsConfigSample] provides default configuration with a 60-second
/// interval and `"SELECT 1"` query.
///
/// @see [org.vorpal.blade.services.analytics.jms.AnalyticsJmsListener]
/// @see [org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet]
/// @see [org.vorpal.blade.services.analytics.sip.AnalyticsConfig]
package org.vorpal.blade.services.analytics;
