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
/// - `CallflowHold` - Processes SIP INVITE requests to place/resume calls on hold
/// - `Terminate` - Handles SIP CANCEL and BYE requests to tear down the call
/// - [HoldMethodNotAllowed] - Fallback callflow for unsupported SIP methods
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
/// - Initial INVITE requests are routed to `CallflowHold`
/// - CANCEL and BYE requests are routed to `Terminate`
/// - Unrecognized methods fall back to [HoldMethodNotAllowed]
///
/// Lifecycle methods `servletCreated` and `servletDestroyed` manage the
/// [HoldSettings] configuration through a static `SettingsManager`.
///
/// ### CallflowHold (framework, `v3.media`)
///
/// Answers the (re-)INVITE with a proper RFC 3264 hold: a 200 OK whose body is
/// OUR OWN inactive answer built from the offer — our `o=` line (stable
/// per-dialog session id, version bumped per answer), our real address with
/// the discard port, `a=inactive` per offered m-line. Offerless refreshes
/// replay the cached answer. Never the legacy `c=0.0.0.0` blackhole.
///
/// ### Terminate (framework)
///
/// Handles CANCEL and BYE to tear down the call with a 200 OK.
///
/// ### HoldMethodNotAllowed
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
