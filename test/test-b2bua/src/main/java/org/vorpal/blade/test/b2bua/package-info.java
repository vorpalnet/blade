/// The starter B2BUA — the first application to read when you begin writing BLADE,
/// and the one most developers copy to start a new project.
///
/// It is a working back-to-back user agent that links two call legs and passes the
/// call through unchanged. It carries no business logic, so nothing hides the one
/// thing it exists to teach: how a whole SIP conversation becomes readable,
/// top-to-bottom code.
///
/// It is written by hand on {@link org.vorpal.blade.framework.v3.AsyncSipServlet},
/// the framework's base servlet — not on the pre-built
/// {@link org.vorpal.blade.framework.v3.B2buaServlet}, which already does all of
/// this. Writing the machinery once, in the open, is the point.
///
/// ## The shape of the app
///
/// A BLADE application is a servlet that *dispatches* and callflows that do the
/// *work*:
///
/// - {@link TestB2buaSipServlet} extends `AsyncSipServlet`. Its `chooseCallflow`
///   maps each inbound method to a callflow; `servletCreated` starts the config
///   manager.
/// - {@link TestB2buaInvite} — the INVITE callflow, the nested-lambda exchange that
///   sets up the call. The heart of the app.
/// - {@link TestB2buaCancel} — forwards a CANCEL to the outbound leg.
/// - {@link TestB2buaPassthru} — everything else (BYE, INFO, OPTIONS, …): forward
///   the request, return the response.
///
/// Each callflow extends {@link org.vorpal.blade.framework.v3.Callflow} and drives
/// its legs with lambda callbacks — `sendRequest`/`sendResponse` hand your lambda
/// the next message, and the callflow's state serializes into the SIP session
/// between messages, so a call survives a node dropping mid-conversation.
///
/// ## Configuration
///
/// {@link TestB2buaConfiguration} is the config shape; each field becomes a form
/// control in the Configurator. {@link TestB2buaSettingsManager} extends
/// {@link org.vorpal.blade.framework.v3.configuration.SettingsManager} and supplies
/// the two things a v3 manager requires: `sample()`, the seed written on first
/// deploy, and `refreshed()`, run on every (re)load.
///
/// The full walkthrough is the tutorial — `README.md` and the slide deck beside it.
///
/// @see TestB2buaSipServlet
/// @see TestB2buaInvite
/// @see org.vorpal.blade.framework.v3.AsyncSipServlet
/// @see org.vorpal.blade.framework.v3.configuration.SettingsManager
package org.vorpal.blade.test.b2bua;
