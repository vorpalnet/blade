/// This package provides a SIP B2BUA (Back-to-Back User Agent) service implementation 
/// that handles call hold functionality. The service extends the Vorpal Blade framework
/// to manage SIP calls with hold and resume capabilities.
///
/// ## Core Components
///
/// - [HoldServlet] - Main servlet implementing the B2BUA with hold transfer capabilities
/// - [HoldSettings] - Configuration class extending `RouterConfig` for service settings
/// - [HoldSettingsSample] - Sample configuration implementation
///
/// ## Call Flow Handlers
///
/// - [HoldInvite] - Processes SIP INVITE requests for hold operations
/// - [HoldBye] - Handles SIP BYE requests to terminate held calls
/// - [NotImplemented] - Placeholder callflow handler for unimplemented SIP methods
///
/// ## Architecture
///
/// The service follows the Vorpal Blade framework's callflow architecture where the
/// main servlet ([HoldServlet]) routes incoming SIP requests to appropriate callflow
/// handlers based on the SIP method. The servlet manages the complete call lifecycle
/// including call establishment, hold/resume operations, and call termination through
/// dedicated callback methods (`callStarted`, `callAnswered`, `callConnected`, etc.).
///
/// The [HoldServlet] extends `B2buaServlet` and uses a `SettingsManager` to handle
/// configuration. It implements the `chooseCallflow` method to route requests to
/// appropriate handlers and provides comprehensive lifecycle management through
/// servlet context events.
///
/// The service is configured as a distributable SIP application with automatic startup
/// and includes comprehensive lifecycle management through servlet context events.
///
/// ## Detailed Class Reference
///
/// ### HoldServlet
///
/// The main servlet class annotated with `@WebListener`, `@SipApplication(distributable=true)`,
/// and `@SipServlet(loadOnStartup=1)`. It extends `B2buaServlet` and routes incoming
/// requests via `chooseCallflow`:
///
/// - Initial INVITE requests are routed to [HoldInvite]
/// - CANCEL and BYE requests are routed to [HoldBye]
/// - Unrecognized methods fall back to [NotImplemented]
///
/// Lifecycle methods `servletCreated` and `servletDestroyed` manage the
/// [HoldSettings] configuration through a static `SettingsManager`.
///
/// ### HoldInvite
///
/// Processes re-INVITE requests to place a call on hold. Reads the SDP body from
/// the incoming request, replaces `a=sendrecv` with `a=inactive` to mute media,
/// copies the `Allow` header, and sends back a 200 OK response. This implements
/// the standard RFC 3264 hold mechanism by setting the media direction to inactive.
///
/// ### HoldBye
///
/// Handles BYE requests to terminate held calls. Creates and sends a simple 200 OK
/// response to acknowledge the session teardown.
///
/// ### NotImplemented
///
/// A fallback callflow handler for SIP methods that the hold service does not support.
/// Responds with 405 (Method Not Allowed) to indicate the method is not implemented.
///
/// ### HoldSettings
///
/// Configuration class extending `RouterConfig` and implementing `Serializable`.
/// Provides the configuration structure for the hold service, inheriting routing
/// configuration capabilities from the framework.
///
/// ### HoldSettingsSample
///
/// Sample configuration subclass of [HoldSettings] used as the default configuration
/// when no external configuration file is present.
///
/// @see HoldServlet
/// @see [org.vorpal.blade.framework.v2.b2bua.B2buaServlet]
/// @see [org.vorpal.blade.framework.v2.callflow.Callflow]
package org.vorpal.blade.services.hold;
