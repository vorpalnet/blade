/// SIP call transfer framework supporting blind, attended, conference, and
/// REFER-based transfer patterns.
///
///
/// ## Transfer Styles
///
/// BLADE supports four transfer styles, each implementing a different SIP
/// signaling pattern:
///
/// <table>
///   <caption>Transfer Styles</caption>
///   <tr><th>Style</th><th>Class</th><th>How It Works</th></tr>
///   <tr>
///     <td>Blind</td>
///     <td>{@link BlindTransfer}</td>
///     <td>Transferor sends REFER; BLADE calls the target directly while the
///         transferor disconnects immediately</td>
///   </tr>
///   <tr>
///     <td>REFER</td>
///     <td>{@link ReferTransfer}</td>
///     <td>BLADE sends REFER to the transferee, who initiates the call to the
///         target; BLADE tracks progress via NOTIFY messages</td>
///   </tr>
///   <tr>
///     <td>Attended</td>
///     <td>{@link AttendedTransfer}</td>
///     <td>Transferor consults with target before completing the transfer
///         (not yet fully implemented)</td>
///   </tr>
///   <tr>
///     <td>Conference</td>
///     <td>{@link ConferenceTransfer}</td>
///     <td>Multiple parties bridged into a conference call
///         (not yet fully implemented)</td>
///   </tr>
/// </table>
///
///
/// ## Blind Transfer Flow
///
/// The most common transfer pattern. When Bob (transferor) wants to transfer
/// Alice (transferee) to Carol (target):
///
/// <ol>
///   <li>Bob sends REFER to BLADE with {@code Refer-To: carol}</li>
///   <li>BLADE responds 202 Accepted</li>
///   <li>BLADE sends NOTIFY to Bob with subscription state "pending"</li>
///   <li>BLADE sends INVITE to Carol, copying configured headers</li>
///   <li>If Carol answers:
///       <ul>
///         <li>BLADE connects Alice to Carol (re-links sessions)</li>
///         <li>BLADE sends NOTIFY to Bob with "terminated" (success)</li>
///         <li>Bob's session is closed</li>
///       </ul></li>
///   <li>If Carol rejects:
///       <ul>
///         <li>BLADE sends NOTIFY to Bob with "terminated;reason=rejected"</li>
///         <li>Alice remains connected to Bob</li>
///       </ul></li>
/// </ol>
///
///
/// ## REFER Transfer Flow
///
/// In this pattern, BLADE delegates the transfer to the transferee:
///
/// <ol>
///   <li>Bob sends REFER to BLADE</li>
///   <li>BLADE sends REFER to Alice with {@code Refer-To} and {@code Referred-By} headers</li>
///   <li>Alice initiates the call to Carol</li>
///   <li>Alice sends NOTIFY messages back to BLADE with progress (100 trying, 200 OK, etc.)</li>
///   <li>On success (200 OK in NOTIFY), BLADE sends BYE to Bob</li>
///   <li>On failure (486 in NOTIFY), BLADE reports decline</li>
/// </ol>
///
///
/// ## Transfer Lifecycle
///
/// The {@link TransferListener} interface provides callbacks at each stage:
///
/// <table>
///   <caption>Transfer Lifecycle Callbacks</caption>
///   <tr><th>Callback</th><th>When It Fires</th></tr>
///   <tr>
///     <td>{@code transferRequested(request)}</td>
///     <td>REFER received from transferor</td>
///   </tr>
///   <tr>
///     <td>{@code transferInitiated(request)}</td>
///     <td>INVITE sent to target</td>
///   </tr>
///   <tr>
///     <td>{@code transferCompleted(response)}</td>
///     <td>Target answered (2xx)</td>
///   </tr>
///   <tr>
///     <td>{@code transferDeclined(response)}</td>
///     <td>Target rejected (4xx/5xx/6xx)</td>
///   </tr>
///   <tr>
///     <td>{@code transferAbandoned(request)}</td>
///     <td>Transferee hung up before target answered</td>
///   </tr>
/// </table>
///
///
/// ## Configuration
///
/// {@link TransferSettings} extends
/// {@link org.vorpal.blade.framework.v2.config.RouterConfig RouterConfig} and adds:
///
/// <ul>
///   <li>{@code defaultTransferStyle} &mdash; which transfer pattern to use
///       (default: blind)</li>
///   <li>{@code transferAllRequests} &mdash; whether non-INVITE methods can be
///       transferred</li>
///   <li>{@code preserveInviteHeaders} &mdash; list of headers to copy from the
///       original INVITE to the target INVITE</li>
///   <li>{@code preserveReferHeaders} &mdash; list of headers to copy to the
///       REFER message (e.g. {@code Referred-By})</li>
///   <li>{@code allow} &mdash; SIP methods listed in the Allow header</li>
/// </ul>
///
/// Since it extends RouterConfig, transfer applications can also use selectors,
/// translation maps, and routing plans for conditional transfer routing.
///
///
/// ## Initial Call Setup
///
/// {@link TransferInitialInvite} extends the B2BUA
/// {@link org.vorpal.blade.framework.v2.b2bua.InitialInvite InitialInvite} to store
/// the original INVITE for later use by transfer operations. The initial INVITE is
/// saved as an application session attribute so that the transfer callflows can
/// reference it when building the target INVITE.
///
///
/// ## Sub-packages
///
/// ### {@linkplain org.vorpal.blade.framework.v2.transfer.api Transfer REST API}
///
/// Provides HTTP endpoints for initiating transfers programmatically. The
/// {@link org.vorpal.blade.framework.v2.transfer.api.TransferAPI TransferAPI}
/// controller enables session inspection and transfer initiation with support for
/// synchronous responses, asynchronous polling, REST callbacks, and JMS messaging.
///
///
/// ## Core Classes
///
/// - {@link Transfer} - Abstract base class with common session management and header copying
/// - {@link TransferInitialInvite} - B2BUA initial INVITE handler that stores the request for later transfer use
/// - {@link BlindTransfer} - Unattended transfer: BLADE calls target directly
/// - {@link ReferTransfer} - REFER-based transfer: transferee calls target
/// - {@link AttendedTransfer} - Consultative transfer (stub)
/// - {@link ConferenceTransfer} - Conference bridging (stub)
/// - {@link TransferSettings} - Configuration with transfer style, headers, and routing
/// - {@link TransferListener} - Lifecycle callbacks for transfer events
/// - {@link TransferCondition} - Conditional routing: match requests to transfer styles
///
/// @see Transfer
/// @see TransferSettings
/// @see TransferListener
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaServlet
package org.vorpal.blade.framework.v2.transfer;
