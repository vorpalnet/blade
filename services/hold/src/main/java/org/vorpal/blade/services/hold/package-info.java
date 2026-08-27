/// Call parking: this service answers a dialog and holds it, so the far end hears
/// nothing and the dialog stays up until someone resumes or hangs up.
///
/// It is a single-dialog UAS, not a B2BUA. There is no second party — the service
/// answers the INVITE itself rather than forwarding it, which is why it has no
/// `callStarted` / `callAnswered` / `callConnected` lifecycle callbacks. Those
/// belong to [org.vorpal.blade.framework.v3.B2buaServlet], which this service
/// does not extend.
///
/// ## Core Components
///
/// - [HoldServlet] - the servlet; dispatches by SIP method
/// - [HoldSettings] - configuration, extending the framework baseline
///   [org.vorpal.blade.framework.v2.config.Configuration]
/// - [HoldSettingsSample] - the configuration written on first deployment
///
/// ## Call Flow Handlers
///
/// - `CallflowHold` - answers an INVITE or re-INVITE with inactive media
/// - `Terminate` - handles CANCEL and BYE to tear the call down
/// - [HoldMethodNotAllowed] - answers 405 for anything else
///
/// ## Architecture
///
/// [HoldServlet] extends [org.vorpal.blade.framework.v3.AsyncSipServlet] and
/// implements one method of consequence, `chooseCallflow`, which picks a
/// callflow per inbound request. Everything else — transaction bookkeeping,
/// glare handling, session replication — is inherited.
///
/// The service is distributable, so its sessions replicate across the cluster
/// and a call survives the loss of the node that answered it.
///
/// ## Detailed Class Reference
///
/// ### HoldServlet
///
/// Annotated `@WebListener`, `@SipApplication(distributable=true)`,
/// `@SipServlet(loadOnStartup=1)` and `@SipListener`. It extends
/// [org.vorpal.blade.framework.v3.AsyncSipServlet] and dispatches via
/// `chooseCallflow`:
///
/// - INVITE (initial or re-INVITE) is routed to `CallflowHold`
/// - CANCEL and BYE are routed to `Terminate`
/// - ACK returns null, letting the container absorb it
/// - anything else falls back to [HoldMethodNotAllowed]
///
/// `servletCreated` and `servletDestroyed` open and close the static
/// `SettingsManager` that owns [HoldSettings].
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
/// Handles CANCEL and BYE to tear down the call with a 200 OK. Hold constructs
/// it with a null listener, since there are no lifecycle callbacks to fire.
///
/// ### HoldMethodNotAllowed
///
/// Answers 405 with an `Allow` header naming the methods this service does
/// support, as RFC 3261 §21.4.5 requires.
///
/// ### HoldSettings
///
/// Extends [org.vorpal.blade.framework.v2.config.Configuration] and carries no
/// settings of its own — the service has nothing to tune. It exists to carry
/// the `@SchemaAbout` identity the Admin Portal reads to render this service's
/// card, and to inherit the baseline `logging` and `session` parameters.
///
/// ### HoldSettingsSample
///
/// The configuration written on first deployment when the operator has not
/// supplied one.
///
/// @see HoldServlet
/// @see [org.vorpal.blade.framework.v3.AsyncSipServlet]
/// @see [org.vorpal.blade.framework.v3.Callflow]
package org.vorpal.blade.services.hold;
