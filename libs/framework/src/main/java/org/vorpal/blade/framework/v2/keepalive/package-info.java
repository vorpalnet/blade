/// This package provides SIP session keep-alive functionality for maintaining active sessions
/// and handling session expiry in SIP servlet applications. The keep-alive mechanism uses
/// re-INVITE requests to refresh RTP streams and prevent session timeouts.
///
/// ## Key Classes
///
/// - [KeepAlive] - Implements session keep-alive by sending re-INVITE requests to both call legs
/// - [KeepAliveExpiry] - Handles session expiry by terminating calls with BYE requests
///
/// ## Architecture
///
/// Both classes extend `ClientCallflow` and implement `SessionKeepAlive.Callback` to integrate
/// with the SIP servlet container's session management system. Each class provides a `handle()`
/// method that processes SIP sessions based on the specific keep-alive scenario:
///
/// - **Session Refresh**: [KeepAlive] sends INVITE requests to refresh RTP streams and negotiates
///   media parameters through SDP exchange between call participants
/// - **Session Termination**: [KeepAliveExpiry] terminates expired sessions by sending BYE requests
///   to both call legs when keep-alive timeouts occur
///
/// ## Integration
///
/// Classes in this package are designed to work with the SIP servlet container's session management
/// system through the `SessionKeepAlive.Callback` interface. The callflow implementations handle
/// the specific SIP messaging required for each scenario while leveraging the broader framework
/// infrastructure for call processing.
///
/// ### KeepAlive Re-INVITE Flow
///
/// The [KeepAlive] callflow performs a three-step SDP exchange to refresh RTP media streams:
///
/// 1. Sends an INVITE to Alice (the first call leg) with no SDP body
/// 2. On receiving Alice's 200 OK with SDP, copies that SDP into a new INVITE sent to Bob
/// 3. On receiving Bob's 200 OK with SDP, copies Bob's SDP into the ACK sent back to Alice
///
/// This ensures both endpoints renegotiate media parameters and keeps the RTP session alive.
/// The linked session (Bob) is resolved via `getLinkedSession()` from the `ClientCallflow`
/// superclass. If the linked session is null, no action is taken.
///
/// ### KeepAliveExpiry Termination Flow
///
/// The [KeepAliveExpiry] callflow terminates an expired call by sending BYE requests to both
/// call legs independently:
///
/// 1. Checks if the primary session is still valid, then sends BYE
/// 2. Resolves the linked session and checks its validity, then sends BYE
///
/// Each BYE is wrapped in its own try-catch block so that a failure on one leg does not
/// prevent termination of the other leg. Sessions that are no longer valid are silently
/// skipped. Both handlers include a defensive null check on the incoming `sipSession`
/// parameter to guard against container edge cases.
///
/// @see javax.servlet.sip.SessionKeepAlive.Callback
/// @see org.vorpal.blade.framework.v2.callflow.ClientCallflow
package org.vorpal.blade.framework.v2.keepalive;
