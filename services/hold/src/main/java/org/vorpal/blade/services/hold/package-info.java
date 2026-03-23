/// # SIP Hold Service
///
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
/// @see HoldServlet
/// @see [org.vorpal.blade.framework.v2.b2bua.B2buaServlet]
/// @see [org.vorpal.blade.framework.v2.callflow.Callflow]
package org.vorpal.blade.services.hold;
