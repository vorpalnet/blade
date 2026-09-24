/// SIP session keep-alive using re-INVITE requests to refresh the session timer
/// and intermediate network state without disturbing media.
///
///
/// ## Why Keep-Alive?
///
/// SIP sessions can time out if no signaling activity occurs for an extended period.
/// NAT bindings expire, firewalls close pinholes, and some endpoints disconnect idle
/// sessions. The keep-alive refreshes both dialogs of a call with one re-INVITE
/// exchange: nothing in either endpoint's session changes, and the round trip
/// refreshes the RFC 4028 session timer and repaints the signaling path through any
/// NAT or firewall in between.
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
///     <td>Refresh the call with one offerless re-INVITE, chained through both dialogs</td>
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
/// ## One offerless re-INVITE, chained through the call
///
/// When the keep-alive timer fires, {@link KeepAlive} sends the dialog an INVITE
/// with no SDP. The endpoint offers its current session in the 2xx; the offer goes
/// to the other leg as a re-INVITE, and that endpoint's answer returns to the first
/// leg in its ACK:
///
/// <pre>
///   Bob                       BLADE                     Alice
///     |&lt;---INVITE (no SDP)------|                         |
///     |----200 OK (Bob SDP)----&gt;|                         |
///     |                         |----INVITE (Bob SDP)----&gt;|
///     |                         |&lt;---200 OK (Alice SDP)---|
///     |&lt;---ACK (Alice SDP)------|                         |
///     |                         |----------ACK-----------&gt;|
/// </pre>
///
/// Each endpoint supplies its own SDP, so BLADE keeps no copy of it. The re-INVITE
/// crosses the rest of a chain of BLADE applications as an ordinary relayed
/// re-INVITE, refreshing every dialog in it. An application that anchors media
/// declines the refresh ({@code Callflow.declineKeepAlive}) and lets the next one
/// downstream claim it, so the re-INVITE reaches it from outside and is re-anchored.
///
/// The first leg retransmits its 2xx for 64*T1 (32 s) waiting for the ACK. A 491
/// on the second leg is retried inside that window (RFC 3261 section 14.1). Any other
/// failure there is acknowledged with every stream rejected, and the call is ended
/// (RFC 3261 section 13.2.2.4). A response that ends the dialog or its invite usage
/// (RFC 5057), or a timeout, ends the call; any other refusal is tried once from
/// the other leg.
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
/// - {@link KeepAlive} - Session refresh: one offerless re-INVITE chained through both dialogs
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
