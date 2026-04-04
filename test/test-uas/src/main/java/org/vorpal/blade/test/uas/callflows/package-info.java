/// SIP callflow implementations for the Test UAS module, providing a variety of
/// response behaviors for testing purposes. Each callflow handles a specific SIP
/// scenario, from basic 200 OK responses to complex REFER-based call transfers
/// with NOTIFY follow-ups.
///
/// ## Key Components
///
/// - [TestInvite] - handles initial INVITE requests with configurable delay, status codes, and call duration via URI parameters
/// - [TestReinvite] - handles mid-dialog re-INVITE requests by responding with a blackhole SDP (inactive media)
/// - [TestOkayResponse] - sends a simple 200 OK response for BYE, CANCEL, and INFO requests
/// - [TestNotImplemented] - sends a 501 Not Implemented response for unsupported SIP methods
/// - [TestRefer] - implements a REFER-based call transfer flow with NOTIFY handshaking
/// - [UasCallflow] - a generic callflow that responds with a configurable status code and waits for ACK
///
/// ## Initial Call Handling
///
/// ### TestInvite
/// [TestInvite] extends {@code InitialInvite} and responds to initial INVITE requests
/// with SDP content (a Qfiniti SIPREC response). It also supports an alternative
/// processing mode using URI parameters:
///
/// - {@code status} - the SIP response code to send (default: 200)
/// - {@code delay} - time to wait before sending the response, supporting suffixes: {@code s} (seconds), {@code m} (minutes), {@code h} (hours), {@code d} (days), {@code y} (years)
/// - {@code duration} - how long to keep the call active before sending BYE (default: 30 seconds)
///
/// The class provides static utility methods {@code inSeconds()} and {@code inMilliseconds()}
/// for parsing human-readable duration strings.
///
/// ## Mid-Dialog Handling
///
/// ### TestReinvite
/// [TestReinvite] extends {@code InitialInvite} and responds to re-INVITE requests
/// with a blackhole SDP ({@code c=IN IP4 0.0.0.0}, {@code a=inactive}), effectively
/// placing media on hold. This simulates a media renegotiation scenario.
///
/// ## Simple Response Callflows
///
/// ### TestOkayResponse
/// [TestOkayResponse] sends a 200 OK for any request it processes. Used by the
/// [UasServlet][org.vorpal.blade.test.uas.UasServlet] to handle BYE, CANCEL, and
/// INFO methods.
///
/// ### TestNotImplemented
/// [TestNotImplemented] sends a 501 Not Implemented response, used as the default
/// handler for SIP methods not explicitly supported by the test UAS.
///
/// ### UasCallflow
/// [UasCallflow] is a configurable callflow that accepts a status code in its
/// constructor and responds with that status. After sending the response, it
/// registers an ACK callback for logging.
///
/// ## Call Transfer
///
/// ### TestRefer
/// [TestRefer] implements a SIP REFER-based call transfer scenario. It:
///
/// 1. Answers the initial INVITE with 200 OK
/// 2. Sends a REFER request to the caller with a {@code Refer-To} address extracted from the URI {@code refer} parameter
/// 3. Expects a NOTIFY with "100 Trying" (implicit subscription)
/// 4. Expects a second NOTIFY with the transfer outcome
/// 5. If the transfer succeeds (200), sends BYE to tear down the original dialog
///
/// The {@code status} URI parameter controls the expected response from the transfer target.
///
/// @see org.vorpal.blade.test.uas.UasServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
/// @see org.vorpal.blade.framework.v2.b2bua.InitialInvite
package org.vorpal.blade.test.uas.callflows;
