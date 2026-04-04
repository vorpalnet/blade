/// This package provides the JMS message consumer for the Vorpal Blade analytics service.
/// It receives JPA entity objects via JMS `ObjectMessage` and persists them to a relational
/// database using JPA, with built-in resilience for database outages.
///
/// ## Key Components
///
/// - [AnalyticsJmsListener] - Message-driven bean that consumes analytics entities from
///   the `jms/BladeAnalyticsDistributedQueue` and persists them via JPA
///
/// ## Architecture
///
/// The analytics system uses an asynchronous pipeline: SIP servlets publish analytics
/// entities (Application, Session, Event) as JMS `ObjectMessage` payloads to a WebLogic
/// distributed queue. The [AnalyticsJmsListener] MDB consumes these messages and merges
/// them into the database using a JPA `EntityManager` backed by the `BladeAnalytics`
/// persistence unit.
///
/// ## Detailed Class Reference
///
/// ### AnalyticsJmsListener
///
/// A `@MessageDriven` EJB with `@TransactionManagement(BEAN)` that listens on the
/// `jms/BladeAnalyticsDistributedQueue`. Handles three JPA entity types:
///
/// - `Application` -- merged via `em.merge(application)`
/// - `Session` -- merged via `em.merge(session)`
/// - `Event` -- persisted via `event.persistEvent(em)` to handle `AttributePK.eventId` updates
///
/// ### Database Resilience
///
/// When a database connection error is detected (via `isDatabaseConnectionError` which
/// inspects the exception cause chain for `SQLTransientConnectionException`,
/// `ConnectException`, `SocketException`, and known error message patterns), the listener:
///
/// 1. Sets a volatile `databaseDown` flag to discard any in-flight messages
/// 2. Suspends JMS message delivery via WebLogic JMX (`MBeanServer.invoke("suspend")`)
///    so messages accumulate in the persistent store
/// 3. Starts a periodic EJB timer (`@Timeout checkDatabaseHealth`) that tests the
///    database connection using a configurable SQL query (`healthCheckSql`)
/// 4. When the connection is restored, resumes JMS delivery via JMX and cancels the timer
///
/// ### JMX Integration
///
/// Uses WebLogic's `MessageDrivenEJBRuntime` MBean (queried by name pattern
/// `com.bea:Type=MessageDrivenEJBRuntime,Name=AnalyticsJmsListener,*`) to suspend
/// and resume message delivery programmatically.
///
/// ### Lifecycle
///
/// - `@PostConstruct init()` -- creates the `EntityManagerFactory` from the
///   `BladeAnalytics` persistence unit; triggers `reportDatabaseDown` on failure
/// - `@PreDestroy cleanup()` -- closes the `EntityManagerFactory` if open
///
/// @see [org.vorpal.blade.services.analytics.sip.AnalyticsSipServlet]
/// @see [org.vorpal.blade.services.analytics.sip.AnalyticsConfig]
/// @see [org.vorpal.blade.framework.v2.analytics.Application]
/// @see [org.vorpal.blade.framework.v2.analytics.Session]
/// @see [org.vorpal.blade.framework.v2.analytics.Event]
package org.vorpal.blade.services.analytics.jms;
