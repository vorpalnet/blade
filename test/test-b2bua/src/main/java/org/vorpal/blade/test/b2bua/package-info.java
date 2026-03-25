/// A sample Back-to-Back User Agent (B2BUA) SIP application that demonstrates
/// how to build a B2BUA service using the BLADE framework. This module serves
/// as both a functional test and a reference implementation for developers
/// creating custom B2BUA applications.
///
/// ## Key Components
///
/// - [SampleB2buaServlet] - the main SIP servlet that extends {@code B2buaServlet}, handling the full call lifecycle
/// - [SampleB2buaConfig] - configuration class defining custom properties ({@code value1}, {@code value2}) with JSON schema annotations
/// - [ConfigSample] - default configuration provider that populates logging, session, and custom parameters
/// - [CancelGlare] - specialized callflow for testing CANCEL glare conditions by deliberately suppressing CANCEL messages
///
/// ## Servlet Lifecycle
///
/// ### Initialization
/// [SampleB2buaServlet] is annotated with {@code @WebListener}, {@code @SipApplication},
/// {@code @SipServlet}, and {@code @SipListener}. On startup, {@code servletCreated()} initializes
/// a [SettingsManager][org.vorpal.blade.framework.v2.config.SettingsManager] with
/// [SampleB2buaConfig] and a [ConfigSample] default instance.
///
/// ### Call Lifecycle Callbacks
/// The servlet overrides all B2BUA lifecycle methods to provide logging and
/// extension points:
///
/// - {@code callStarted()} - outbound INVITE to Bob
/// - {@code callAnswered()} - final response relayed to Alice
/// - {@code callConnected()} - ACK processing
/// - {@code callCompleted()} - BYE from either party
/// - {@code callDeclined()} - error response from Bob
/// - {@code callAbandoned()} - CANCEL from Alice
/// - {@code requestEvent()} - mid-dialog requests (re-INVITE, INFO, UPDATE)
/// - {@code responseEvent()} - mid-dialog responses
///
/// ### Session Monitoring
/// The servlet includes session lifecycle logging at {@code FINER} level for both
/// {@code SipApplicationSession} and {@code SipSession} events (created, destroyed,
/// expired, readyToInvalidate).
///
/// ## Configuration
///
/// ### SampleB2buaConfig
/// Extends the framework {@code Configuration} class and adds two custom string
/// properties annotated with {@code @JsonPropertyDescription} and
/// {@code @JsonSchemaTitle} for JSON schema generation.
///
/// ### ConfigSample Defaults
/// Provides default values using {@code LogParametersDefault} for logging and
/// {@code SessionParametersDefault} for session management.
///
/// ## CANCEL Glare Testing
///
/// ### CancelGlare Callflow
/// [CancelGlare] extends {@code Callflow} and deliberately does not send a CANCEL
/// message, instead logging a severe message and returning a 200 OK. This is used
/// to test race conditions where a CANCEL and a final response cross in transit.
///
/// ## Related Packages
///
/// ### org.vorpal.blade.applications.console.config.test
/// Remote EJB interfaces for testing the BLADE console's configuration management
/// capabilities. Defines [HelloBeanRemote][org.vorpal.blade.applications.console.config.test.HelloBeanRemote]
/// for fire-and-forget service bean invocation and
/// [HelloWorld][org.vorpal.blade.applications.console.config.test.HelloWorld] for
/// request-response round-trip EJB communication with RMI error handling. These
/// interfaces enable integration testing between the console administration module
/// and deployed SIP services.
///
/// @see org.vorpal.blade.applications.console.config.test
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaServlet
/// @see org.vorpal.blade.framework.v2.config.SettingsManager
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
package org.vorpal.blade.test.b2bua;
