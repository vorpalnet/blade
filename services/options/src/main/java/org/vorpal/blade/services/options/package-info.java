/// This package provides a SIP OPTIONS ping responder service for the Vorpal Blade
/// framework. It handles SIP OPTIONS requests by returning a 200 OK response with
/// configurable capability headers, enabling SIP endpoint keep-alive and capability
/// discovery.
///
/// ## Key Components
///
/// - [OptionsSipServlet] - Main servlet that routes all incoming requests to the OPTIONS callflow
/// - [OptionsCallflow] - Callflow handler that builds and sends the 200 OK response with capability headers
/// - [OptionsSettings] - Configuration class defining the response header values
/// - [OptionsSettingsSample] - Default configuration with standard SIP capability values
///
/// ## Architecture
///
/// The service extends `AsyncSipServlet` rather than `B2buaServlet` since it only needs
/// to respond to requests without establishing back-to-back call legs. The
/// `chooseCallflow` method always returns an [OptionsCallflow] instance regardless of
/// the SIP method, making it a simple request/response handler.
///
/// ## Detailed Class Reference
///
/// ### OptionsSipServlet
///
/// Main servlet annotated with `@WebListener`, `@SipApplication(distributable=true)`,
/// and `@SipServlet(loadOnStartup=1)`. Extends `AsyncSipServlet` for lightweight
/// request processing. The `chooseCallflow` method unconditionally returns a new
/// [OptionsCallflow] instance. Manages configuration through a static
/// `SettingsManager<OptionsSettings>` initialized in `servletCreated`.
///
/// ### OptionsCallflow
///
/// Callflow handler extending `Callflow` and implementing `Serializable`. The `process`
/// method reads the current [OptionsSettings] configuration and constructs a 200 OK
/// response with the following headers:
///
/// - `Accept` -- supported content types (e.g., `application/sdp`)
/// - `Accept-Language` -- supported languages (e.g., `en`)
/// - `Allow` -- supported SIP methods (INVITE, ACK, BYE, CANCEL, etc.)
/// - `User-Agent` -- server identification (e.g., `OCCAS`)
/// - `Allow-Events` -- supported event packages (e.g., `talk, hold`)
///
/// ### OptionsSettings
///
/// Configuration class extending `Configuration` and implementing `Serializable`.
/// Defines six protected string fields for the response headers: `accept`,
/// `acceptLanguage`, `allow`, `supported`, `userAgent`, and `allowEvents`.
/// Each field has standard getter/setter methods.
///
/// ### OptionsSettingsSample
///
/// Default configuration that sets logging to `WARNING` level, uses
/// `SessionParametersDefault`, and populates the capability headers with standard
/// values including support for all common SIP methods, `replaces` extension,
/// and `talk`/`hold` event packages.
///
/// @see OptionsSipServlet
/// @see OptionsCallflow
/// @see [org.vorpal.blade.framework.v2.AsyncSipServlet]
/// @see [org.vorpal.blade.framework.v2.config.Configuration]
package org.vorpal.blade.services.options;
