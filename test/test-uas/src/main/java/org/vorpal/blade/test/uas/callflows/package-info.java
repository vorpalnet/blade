/// Endpoint-mode SIP callflows for the Test UAS. These run when the initial
/// INVITE carries a `status`, `delay`, or `refer` Request-URI parameter; an
/// INVITE with none of those is forwarded by the B2BUA strip-and-forward path
/// instead (see [UasServlet][org.vorpal.blade.test.uas.UasServlet]).
///
/// ## Key Components
///
/// - [TestInvite] - answers an initial INVITE per the `status`/`delay`
///   Request-URI parameters
/// - [TestOkayResponse] - sends a simple 200 OK for in-dialog BYE, CANCEL, INFO
/// - [TestNotImplemented] - sends 501 Not Implemented for unsupported methods
/// - [TestRefer] - REFER-based call transfer flow with NOTIFY handshaking
///
/// ## Initial Call Handling
///
/// ### TestInvite
/// [TestInvite] extends [org.vorpal.blade.framework.v2.callflow.Callflow]. On
/// the initial INVITE it:
///
/// 1. Reads `status` (default `200`) and `delay` from the Request-URI
/// 2. Sends the response — a 2xx answer carries a blackhole/mute SDP
///    (`c=0.0.0.0`, `a=inactive`) built from the caller's offer via
///    [Callflow#hold][org.vorpal.blade.framework.v2.callflow.Callflow]
/// 3. For an answered (2xx) call with `delay > 0`, holds the call up for
///    `delay`, then sends `BYE`
///
/// `delay` accepts a bare integer (milliseconds) or an `ms`/`s`/`m`/`h` suffix
/// — e.g. `delay=5000`, `delay=5s`, `delay=500ms`, `delay=2m`.
///
/// ## Mid-Dialog Handling
///
/// In-dialog re-INVITEs are answered with the framework's
/// [CallflowHold][org.vorpal.blade.framework.v2.callflow.CallflowHold], which
/// returns a blackhole hold answer (and correctly replays it for offerless
/// keep-alive re-INVITEs).
///
/// ## Simple Response Callflows
///
/// [TestOkayResponse] sends 200 OK; [TestNotImplemented] sends 501. Both are
/// used by [UasServlet][org.vorpal.blade.test.uas.UasServlet] for in-dialog
/// requests on an endpoint-mode dialog.
///
/// ## Call Transfer
///
/// ### TestRefer
/// [TestRefer] runs when the INVITE carries a `refer` Request-URI parameter:
///
/// 1. Answers the initial INVITE with 200 OK
/// 2. Sends REFER with `Refer-To` taken from the `refer` parameter
/// 3. Expects NOTIFY with "100 Trying" (implicit subscription)
/// 4. Expects a second NOTIFY with the transfer outcome
/// 5. On success (200 in the SIP fragment), sends BYE to tear down the dialog
///
/// The `status` parameter is appended to the `Refer-To` URI to control the
/// expected response from the transfer target.
///
/// @see org.vorpal.blade.test.uas.UasServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
/// @see org.vorpal.blade.framework.v2.callflow.CallflowHold
package org.vorpal.blade.test.uas.callflows;
