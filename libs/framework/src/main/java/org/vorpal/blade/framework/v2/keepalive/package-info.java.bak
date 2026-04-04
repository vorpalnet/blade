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
/// ## Functionality
///
/// The keep-alive process involves:
/// 
/// 1. **Session Refresh**: [KeepAlive] sends INVITE requests to refresh RTP streams
/// 2. **SDP Exchange**: Negotiates media parameters between call participants  
/// 3. **Session Termination**: [KeepAliveExpiry] terminates expired sessions
///
/// Both classes extend [ClientCallflow] and implement [SessionKeepAlive.Callback] to integrate
/// with the SIP servlet container's session management system. Each class provides a `handle()` 
/// method that processes the specific SIP session based on the keep-alive scenario.
///
/// ## Call Flow Diagrams
///
/// Both classes include PlantUML sequence diagrams in their source documentation that illustrate
/// the re-INVITE call flow for RTP stream refresh, showing the interaction between Alice, the
/// Blade framework, and Bob during the keep-alive process.
///
/// @see javax.servlet.sip.SessionKeepAlive.Callback
/// @see org.vorpal.blade.framework.v2.callflow.ClientCallflow
package org.vorpal.blade.framework.v2.keepalive;
