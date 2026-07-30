/// Event collection for SIP applications: what to capture from a call, and how
/// to pull it out of a SIP message.
///
///
/// ## Overview
///
/// The analytics subsystem captures events from SIP call lifecycles and HTTP REST
/// operations, extracts configurable attributes via regex patterns, and publishes
/// each as a CloudEvent on the BLADE event bus. Whoever wants them subscribes —
/// the analytics service persists them to a relational database; another
/// application may act on the same events without knowing that one exists.
///
/// **This package produces facts; it does not know about a database.** It used to:
/// the JPA entities lived here and the producer filled in the rows — primary
/// keys, foreign keys and all — which meant every consumer had to be a database,
/// on BLADE's classpath, reading Java-serialized objects. Those entities now live
/// with the service that owns the schema, in
/// `org.vorpal.blade.services.analytics.model`, and thirty WARs stopped shipping
/// persistence classes they never used.
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
///   <li><b>Publish events</b> &mdash;
///       {@link Analytics#sendEvent(org.vorpal.blade.framework.v3.events.AnalyticsEvent)}
///       closes the event and puts it on the bus as a CloudEvent</li>
///   <li><b>Whoever wants it, subscribes</b> &mdash; the analytics service writes
///       the events its catalog marks persisted into the database; any other
///       application may subscribe to the same events independently, and each
///       subscriber receives its own copy</li>
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
/// ## What goes on the wire
///
/// Each event is a CloudEvent, and its `type` is the identity a subscriber
/// selects on. The framework's own names — the eleven `callStarted`,
/// `transferRequested` and the rest, a closed set defined in framework code —
/// each get a type of their own, so an application can subscribe to precisely
/// the events it acts on. A name an operator invents in the {@code events} map
/// below has no declaration to select on and travels as
/// {@code net.vorpal.blade.call.event} with the name in the payload, which is
/// why adding the eleven broke nobody's existing configuration. See
/// {@link org.vorpal.blade.framework.v3.events.BladeEventTypes}.
///
/// The correlator is the Vorpal-ID **plus the call's birth instant**, in the
/// envelope's {@code subject}. A Vorpal-ID is 32 bits and is only checked for
/// uniqueness among live sessions, so ids are reused: the id alone is a
/// correlator, and only the pair is an identity.
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
/// - {@link Analytics} - Configuration and event factory: creates events, extracts attributes, publishes to the bus
/// - {@link EventSelector} - Defines which attributes to extract for a specific event type
/// - {@link org.vorpal.blade.framework.v3.events.AnalyticsEvent} - One fact, while it is still being assembled
/// - {@link org.vorpal.blade.framework.v3.events.AnalyticsEventMapper} - Shapes the CloudEvents this package emits
///
/// ### HTTP Integration
///
/// - {@link AnalyticsFilter} - Servlet filter for REST endpoint analytics
/// - {@link BufferedRequestWrapper} - Request body buffering for re-reading
/// - {@link BufferedResponseWrapper} - Response body buffering for analytics capture
///
/// @see Analytics
/// @see EventSelector
/// @see org.vorpal.blade.framework.v3.events.AnalyticsEvent
/// @see org.vorpal.blade.framework.v3.events.BladeEventTypes
package org.vorpal.blade.framework.v2.analytics;
