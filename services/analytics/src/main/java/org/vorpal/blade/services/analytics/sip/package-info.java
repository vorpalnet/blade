/// This package provides the SIP-facing components of the Vorpal Blade analytics service.
/// It implements a B2BUA servlet that captures call lifecycle events and publishes analytics
/// data for persistence by the JMS listener.
///
/// ## Key Components
///
/// - [AnalyticsSipServlet] - B2BUA servlet that captures SIP call lifecycle events
/// - [AnalyticsConfig] - Configuration class with database health-check settings
/// - [AnalyticsConfigSample] - Default configuration with sample analytics parameters
///
/// ## Architecture
///
/// The analytics SIP servlet operates as a B2BUA that observes call lifecycle transitions
/// (started, answered, connected, completed, declined, abandoned) and session lifecycle
/// events (created, destroyed, expired, ready-to-invalidate). These events are logged and
/// can be published as analytics entities to the JMS queue for database persistence by
/// [AnalyticsJmsListener][org.vorpal.blade.services.analytics.jms.AnalyticsJmsListener].
///
/// ## Detailed Class Reference
///
/// ### AnalyticsSipServlet
///
/// Main servlet annotated with `@SipApplication(distributable=true)` and
/// `@SipServlet(loadOnStartup=1)`. Extends `B2buaServlet` and implements both
/// `B2buaListener` and `SipApplicationSessionListener`. Maintains a static
/// `SettingsManager<AnalyticsConfig>` (`settingsManager`) and a static `Application`
/// object. All lifecycle callback methods (`callStarted`, `callAnswered`, `callConnected`,
/// `callCompleted`, `callDeclined`, `callAbandoned`, `sessionCreated`, `sessionDestroyed`,
/// `sessionExpired`, `sessionReadyToInvalidate`) log the event at INFO level with the
/// associated SIP message or application session.
///
/// ### AnalyticsConfig
///
/// Configuration class extending the framework's base `Configuration` and implementing
/// `Serializable`. Adds two analytics-specific fields:
///
/// - `healthCheckInterval` (Integer) -- seconds between database health-check attempts
///   when the database is down (used by [AnalyticsJmsListener][org.vorpal.blade.services.analytics.jms.AnalyticsJmsListener])
/// - `healthCheckSql` (String) -- SQL statement to verify database connectivity
///   (e.g., `"SELECT 1"`)
///
/// ### AnalyticsConfigSample
///
/// Default configuration subclass that sets logging to `FINER`, uses
/// `SessionParametersDefault`, includes an `AnalyticsB2buaSample` analytics configuration,
/// and configures health checks with a 60-second interval and `"SELECT 1"` SQL query.
///
/// @see [org.vorpal.blade.services.analytics.jms.AnalyticsJmsListener]
/// @see [org.vorpal.blade.framework.v2.b2bua.B2buaServlet]
/// @see [org.vorpal.blade.framework.v2.config.Configuration]
package org.vorpal.blade.services.analytics.sip;
