/// SIP session keep-alive using re-INVITE requests to refresh RTP streams and
/// prevent session timeouts.
///
///
/// ## Why Keep-Alive?
///
/// SIP sessions can time out if no signaling activity occurs for an extended period.
/// NAT bindings expire, firewalls close pinholes, and some endpoints disconnect idle
/// sessions. The keep-alive mechanism sends periodic re-INVITE requests to both call
/// legs, forcing SDP renegotiation and refreshing all intermediate network state.
///
///
/// ## Configuration
///
/// Keep-alive is controlled by the {@code "keepAlive"} section within the
/// {@code "session"} configuration:
///
/// <pre>{@code
/// "session": {
///   "expiration": 60,
///   "keepAlive": {
///     "style": "UPDATE",
///     "sessionExpires": 1800,
///     "minSE": 90
///   }
/// }
/// }</pre>
///
/// <table>
///   <caption>Keep-Alive Styles</caption>
///   <tr><th>Style</th><th>Behavior</th></tr>
///   <tr>
///     <td>{@code DISABLED}</td>
///     <td>No keep-alive (default)</td>
///   </tr>
///   <tr>
///     <td>{@code UPDATE}</td>
///     <td>Use SIP UPDATE method for session refresh</td>
///   </tr>
///   <tr>
///     <td>{@code REINVITE}</td>
///     <td>Use re-INVITE with SDP renegotiation</td>
///   </tr>
/// </table>
///
/// The {@code sessionExpires} value (in seconds) controls how often the refresh
/// fires. The {@code minSE} value is the minimum acceptable session interval
/// negotiated with the remote endpoint.
///
///
/// ## Re-INVITE SDP Exchange
///
/// When the keep-alive timer fires, {@link KeepAlive} performs a three-step SDP
/// exchange across both call legs:
///
/// <pre>
///   Alice                     BLADE                      Bob
///     |                         |                         |
///     |&lt;---INVITE (no SDP)------|                         |
///     |----200 OK (SDP)--------&gt;|                         |
///     |                         |---INVITE (Alice SDP)---&gt;|
///     |                         |&lt;----200 OK (Bob SDP)----|
///     |&lt;---ACK (Bob SDP)--------|                         |
///     |                         |--------ACK-------------&gt;|
///     |                         |                         |
/// </pre>
///
/// This forces both endpoints to renegotiate media parameters, refreshing RTP
/// keep-alive timers on all intermediate network devices.
///
///
/// ## Session Expiry
///
/// When the session timer expires without a successful refresh, {@link KeepAliveExpiry}
/// terminates the call by sending BYE to both legs independently. Each BYE is wrapped
/// in its own try-catch block so that a failure on one leg does not prevent termination
/// of the other. Sessions that are no longer valid are silently skipped.
///
///
/// ## Core Classes
///
/// - {@link KeepAlive} - Session refresh: re-INVITE with three-step SDP exchange
/// - {@link KeepAliveExpiry} - Session termination: BYE to both legs on timeout
///
/// Both classes extend
/// {@link org.vorpal.blade.framework.v2.callflow.ClientCallflow ClientCallflow} and
/// implement {@code SessionKeepAlive.Callback}. The SIP container calls their
/// {@code handle(SipSession)} method when the keep-alive timer fires or expires.
///
/// @see KeepAlive
/// @see KeepAliveExpiry
/// @see org.vorpal.blade.framework.v2.config.KeepAliveParameters
package org.vorpal.blade.framework.v2.keepalive;
