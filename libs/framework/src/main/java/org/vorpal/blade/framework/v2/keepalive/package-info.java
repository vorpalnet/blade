/// SIP session keep-alive using re-INVITE requests to refresh the session timer
/// and intermediate network state without disturbing media.
///
///
/// ## Why Keep-Alive?
///
/// SIP sessions can time out if no signaling activity occurs for an extended period.
/// NAT bindings expire, firewalls close pinholes, and some endpoints disconnect idle
/// sessions. The keep-alive mechanism periodically re-INVITEs both call dialogs,
/// re-offering each endpoint the media it already advertised. Nothing in the SDP
/// changes, so the endpoint keeps its media as-is; the round trip refreshes the
/// RFC 4028 session timer and repaints the signaling path through any NAT or
/// firewall in between.
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
///     "style": "REINVITE",
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
///     <td>{@code REINVITE}</td>
///     <td>Refresh each dialog with a re-INVITE re-offering its negotiated media</td>
///   </tr>
/// </table>
///
/// The {@code sessionExpires} value (in seconds) controls how often the refresh
/// fires. The {@code minSE} value is the minimum acceptable session interval
/// negotiated with the remote endpoint.
///
/// Only one application in a chain of B2BUAs drives the refresh. The first app to
/// handle the call claims it by stamping {@code refresher=uac} on the propagated
/// {@code Session-Expires}; downstream apps that see the claim stand down. See
/// {@code Callflow.applyKeepAlive}.
///
///
/// ## Independent per-leg refresh
///
/// When the keep-alive timer fires, {@link KeepAlive} refreshes each dialog on its
/// own transaction, offering the media the *peer* leg already advertised (cached
/// per session in {@code Callflow.LAST_SDP} as messages flow):
///
/// <pre>
///   Alice                     BLADE                      Bob
///     |&lt;---INVITE (Bob SDP)-----|                         |
///     |----200 OK (Alice SDP)--&gt;|                         |
///     |&lt;---ACK-----------------&gt;|                         |
///     |                         |---INVITE (Alice SDP)---&gt;|   (independent)
///     |                         |&lt;----200 OK (Bob SDP)----|
///     |                         |--------ACK-------------&gt;|
/// </pre>
///
/// The two legs do not depend on each other. An earlier design chained one
/// offerless re-INVITE through both dialogs, so a non-2xx from the second leg left
/// the first leg's {@code 200 OK} unacknowledged and the endpoint tore the dialog
/// down (RFC 3261) — killing the call keep-alive exists to preserve. Refreshing
/// each leg with its peer's cached SDP removes that coupling: a failure on one leg
/// cannot orphan the other. A leg with no cached peer SDP is skipped for that
/// cycle rather than sent an offerless re-INVITE it could not answer.
///
///
/// ## Session Expiry
///
/// When the session timer expires without a successful refresh, {@link KeepAliveExpiry}
/// terminates the call by sending BYE to both dialogs independently. Each BYE is wrapped
/// in its own try-catch block so that a failure on one dialog does not prevent termination
/// of the other. Sessions that are no longer valid are silently skipped.
///
///
/// ## Core Classes
///
/// - {@link KeepAlive} - Session refresh: independent per-leg re-INVITE re-offering cached media
/// - {@link KeepAliveExpiry} - Session termination: BYE to both dialogs on timeout
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
