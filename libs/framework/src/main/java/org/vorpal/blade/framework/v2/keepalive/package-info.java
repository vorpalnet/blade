/// # SIP Session Keep-Alive Framework
///
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
/// @see javax.servlet.sip.SessionKeepAlive.Callback
/// @see org.vorpal.blade.framework.v2.callflow.ClientCallflow
package org.vorpal.blade.framework.v2.keepalive;
