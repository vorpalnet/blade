/// SIP callflow implementations for the Test UAS module, providing configurable
/// response behaviors for load testing and functional testing. Each callflow
/// handles a specific SIP scenario, from high-performance INVITE processing
/// with configurable delays and auto-BYE to complex REFER-based call transfers.
///
/// ## Key Components
///
/// - [TestInvite] - handles initial INVITE requests with configurable delay, status
///   codes, call duration, error map lookup, and SDP content; the primary callflow
///   for load testing
/// - [TestReinvite] - handles mid-dialog re-INVITE requests by responding with a
///   blackhole SDP (inactive media at 0.0.0.0)
/// - [TestOkayResponse] - sends a simple 200 OK response for BYE, CANCEL, and INFO
/// - [TestNotImplemented] - sends a 501 Not Implemented response for unsupported methods
/// - [TestRefer] - implements a REFER-based call transfer flow with NOTIFY handshaking
/// - [UasCallflow] - a generic callflow that responds with a configurable status code
///
/// ## Initial Call Handling
///
/// ### TestInvite
/// [TestInvite] extends `InitialInvite` and is the workhorse for load testing.
/// On each incoming INVITE, it:
///
/// 1. Reads default behavior from
///    [TestUasConfig][org.vorpal.blade.test.uas.config.TestUasConfig] via
///    [UasServlet.settingsManager][org.vorpal.blade.test.uas.UasServlet]
/// 2. Checks for request URI parameter overrides:
///    - `status` — SIP response code (default from config)
///    - `delay` — time to wait before responding, supports suffixes: `s` (seconds),
///      `m` (minutes), `h` (hours), `d` (days), `y` (years)
///    - `duration` — call hold time before auto-BYE (same suffix format)
/// 3. Checks the error map — if the called phone number matches, that status wins
/// 4. If delay > 0, schedules a timer via `scheduleTimer()` before sending
/// 5. Sends the response with SDP content for 2xx responses
/// 6. For successful responses with duration > 0, schedules an auto-BYE timer
///    to tear down the call after the configured period
///
/// The class provides static utility methods `inSeconds()` and `inMilliseconds()`
/// for parsing human-readable duration strings.
///
/// ## Mid-Dialog Handling
///
/// ### TestReinvite
/// [TestReinvite] extends `InitialInvite` and responds to re-INVITE requests
/// with a blackhole SDP (`c=IN IP4 0.0.0.0`, `a=inactive`), effectively
/// placing media on hold. This simulates a media renegotiation scenario.
///
/// ## Simple Response Callflows
///
/// ### TestOkayResponse
/// [TestOkayResponse] sends a 200 OK for any request. Used by
/// [UasServlet][org.vorpal.blade.test.uas.UasServlet] for BYE, CANCEL, and INFO.
///
/// ### TestNotImplemented
/// [TestNotImplemented] sends 501 Not Implemented, the default handler for SIP
/// methods not explicitly supported by the test UAS.
///
/// ### UasCallflow
/// [UasCallflow] accepts a status code in its constructor and responds with that
/// status. After sending, it registers an ACK callback for logging.
///
/// ## Call Transfer
///
/// ### TestRefer
/// [TestRefer] implements a SIP REFER-based call transfer scenario:
///
/// 1. Answers the initial INVITE with 200 OK
/// 2. Sends REFER with `Refer-To` address from the URI `refer` parameter
/// 3. Expects NOTIFY with "100 Trying" (implicit subscription)
/// 4. Expects second NOTIFY with the transfer outcome
/// 5. On success (200 in SIP fragment), sends BYE to tear down the original dialog
///
/// The `status` URI parameter controls the expected response from the transfer target.
///
/// @see org.vorpal.blade.test.uas.UasServlet
/// @see org.vorpal.blade.test.uas.config.TestUasConfig
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
/// @see org.vorpal.blade.framework.v2.b2bua.InitialInvite
package org.vorpal.blade.test.uas.callflows;
