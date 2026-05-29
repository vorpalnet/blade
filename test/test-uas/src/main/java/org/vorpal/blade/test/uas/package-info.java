/// A SIP test server built on the BLADE framework. The Test UAS is the
/// counterpart to the Test UAC — together they form a complete SIP load
/// testing tool for production call center performance tuning.
///
/// ## Two modes, chosen from the Request-URI
///
/// [UasServlet] picks a mode per-call from the initial INVITE's Request-URI —
/// there is no configuration toggle:
///
/// - **Strip-and-forward (B2BUA)** — when the INVITE carries none of `status`,
///   `delay`, or `refer`, it is forwarded to its Request-URI. A multipart
///   (e.g. SIPREC) body is stripped down to just its `application/sdp` part so
///   a plain softphone can parse it.
/// - **Endpoint (UAS)** — when the INVITE carries `status`, `delay`, or
///   `refer`, the call is answered locally:
///   - `status` / `delay` →
///     [TestInvite][org.vorpal.blade.test.uas.callflows.TestInvite]
///   - `refer` → [TestRefer][org.vorpal.blade.test.uas.callflows.TestRefer]
///
/// Example endpoint call: `sip:target@uas.test;status=200;delay=5s` answers
/// with a muted (blackhole) SDP and sends `BYE` after 5 seconds.
///
/// The chosen mode is stamped on the application session so in-dialog requests
/// (re-INVITE, BYE, …) route the same way as the initial INVITE.
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.test.uas.callflows]
/// The endpoint-mode callflows: [TestInvite][org.vorpal.blade.test.uas.callflows.TestInvite]
/// (status/delay + auto-BYE), [TestRefer][org.vorpal.blade.test.uas.callflows.TestRefer]
/// (transfer test), and simple 200/501 responders. Re-INVITEs reuse the
/// framework's [CallflowHold][org.vorpal.blade.framework.v2.callflow.CallflowHold].
///
/// ### [org.vorpal.blade.test.uas.config]
/// [TestUasConfig][org.vorpal.blade.test.uas.config.TestUasConfig] carries no
/// app-specific settings — behavior comes from the Request-URI — only the
/// inherited logging and session parameters.
///
/// @see org.vorpal.blade.test.uas.callflows
/// @see org.vorpal.blade.test.uas.config
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaServlet
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaListener
package org.vorpal.blade.test.uas;
