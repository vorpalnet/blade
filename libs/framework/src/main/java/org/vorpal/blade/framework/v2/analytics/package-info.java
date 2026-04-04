/// Event collection and publishing for SIP applications with JMS integration
/// and database persistence.
///
///
/// ## Overview
///
/// The analytics subsystem captures events from SIP call lifecycles and HTTP REST
/// operations, extracts configurable attributes via regex patterns, and publishes
/// them asynchronously to a JMS queue. A downstream consumer (typically the
/// analytics service) persists events to a relational database for reporting.
///
///
/// ## How It Works
///
/// <ol>
///   <li><b>Configure events</b> &mdash; define which events to capture and what
///       attributes to extract from SIP headers or JSON payloads, using
///       {@link EventSelector} objects with regex-based
///       {@link org.vorpal.blade.framework.v2.config.AttributeSelector AttributeSelector}
///       rules</li>
///   <li><b>Create events</b> &mdash; the framework calls
///       {@link Analytics#createEvent(String, javax.servlet.sip.SipServletMessage)}
///       at lifecycle points (e.g. callStarted, callCompleted), extracting
///       configured attributes automatically</li>
///   <li><b>Publish events</b> &mdash; {@link Analytics#sendEvent(Event)} serializes
///       the event and sends it via {@link JmsPublisher} as a JMS
///       {@code ObjectMessage}</li>
///   <li><b>Persist events</b> &mdash; a downstream JMS consumer deserializes the
///       {@link Event} JPA entity and persists it to the database</li>
/// </ol>
///
///
/// ## Configuration
///
/// Analytics is configured in the {@code "analytics"} section of each application's
/// JSON config file. The {@link Analytics} class is itself the configuration object,
/// containing a map of event names to {@link EventSelector} objects:
///
/// <pre>{@code
/// "analytics": {
///   "enabled": false,
///   "events": {
///     "callStarted": {
///       "attributes": [
///         { "id": "caller", "attribute": "From", "pattern": "^.*sip:(.*)@.*$", "expression": "$1" },
///         { "id": "callee", "attribute": "To",   "pattern": "^.*sip:(.*)@.*$", "expression": "$1" }
///       ]
///     },
///     "callCompleted": {
///       "attributes": [
///         { "id": "disconnector", "attribute": "From", "pattern": "^.*sip:(.*)@.*$", "expression": "$1" }
///       ]
///     }
///   }
/// }
/// }</pre>
///
/// Each attribute uses the same regex pattern and expression syntax as
/// {@link org.vorpal.blade.framework.v2.config.Selector Selector}: named capturing
/// groups in the pattern, variable substitution in the expression.
///
///
/// ## Sample Implementations
///
/// The package includes pre-built analytics configurations that build on each other:
///
/// <ul>
///   <li>{@link AnalyticsAsyncSipServletSample} &mdash; base: defines {@code start}
///       and {@code stop} events with server and servlet name</li>
///   <li>{@link AnalyticsB2buaSample} &mdash; extends base: adds {@code callStarted},
///       {@code callCompleted}, and {@code callDeclined} events with caller/callee
///       attributes</li>
///   <li>{@link AnalyticsTransferSample} &mdash; extends B2BUA: adds
///       {@code transferRequested}, {@code transferInitiated}, and
///       {@code transferDeclined} events</li>
/// </ul>
///
/// Applications typically extend the appropriate sample and add custom events.
///
///
/// ## Data Model
///
/// Events are stored as JPA entities in a relational database:
///
/// <ul>
///   <li>{@link Application} &mdash; one row per application deployment, tracking
///       server name, version, and start/stop timestamps</li>
///   <li>{@link Session} &mdash; one row per {@code SipApplicationSession}, tracking
///       creation and destruction timestamps</li>
///   <li>{@link Event} &mdash; one row per event, linked to a session, with a name
///       and timestamp</li>
///   <li>{@link Attribute} &mdash; one row per extracted attribute, linked to an event
///       via composite key ({@link AttributePK})</li>
/// </ul>
///
///
/// ## HTTP Analytics
///
/// {@link AnalyticsFilter} is a servlet filter that captures analytics from HTTP
/// REST endpoints (e.g. the Transfer REST API). It buffers request and response
/// bodies using {@link BufferedRequestWrapper} and {@link BufferedResponseWrapper},
/// matches the request path against configured event names, and creates events from
/// JSON payloads. The filter correlates HTTP requests with SIP sessions via a
/// {@code ThreadLocal} set by the SIP servlet.
///
///
/// ## Core Classes
///
/// ### Engine
///
/// - {@link Analytics} - Configuration and event factory: creates events, extracts attributes, publishes via JMS
/// - {@link EventSelector} - Defines which attributes to extract for a specific event type
/// - {@link JmsPublisher} - JMS connection lifecycle and {@code ObjectMessage} publishing
///
/// ### JPA Entities
///
/// - {@link Application} - Application deployment metadata
/// - {@link Session} - SIP application session lifecycle
/// - {@link Event} - Individual analytics event with attributes
/// - {@link Attribute} - Key-value attribute linked to an event
///
/// ### HTTP Integration
///
/// - {@link AnalyticsFilter} - Servlet filter for REST endpoint analytics
/// - {@link BufferedRequestWrapper} - Request body buffering for re-reading
/// - {@link BufferedResponseWrapper} - Response body buffering for analytics capture
///
/// @see Analytics
/// @see EventSelector
/// @see Event
/// @see JmsPublisher
package org.vorpal.blade.framework.v2.analytics;
