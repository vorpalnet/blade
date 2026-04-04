/// Back-to-Back User Agent (B2BUA) implementation built on BLADE's lambda callflow
/// pattern. Approximately 85% of all BLADE applications use this package as their
/// foundation.
///
///
/// ## What is a B2BUA?
///
/// A B2BUA sits between two call legs&mdash;Alice (the caller) and Bob (the callee)&mdash;
/// terminating the inbound SIP dialog and creating a corresponding outbound dialog.
/// From Alice's perspective, she is talking to the application. From Bob's perspective,
/// the application is calling him. This gives the application full control over call
/// routing, header manipulation, SDP modification, recording, and call control.
///
///
/// ## Quick Start
///
/// To build a B2BUA application, extend {@link B2buaServlet} and implement the
/// lifecycle callback methods. The framework handles session linking, message
/// forwarding, glare detection, and state management automatically. Your code
/// focuses entirely on business logic:
///
/// {@snippet :
/// public class MyServlet extends B2buaServlet {
///
///     public void servletCreated(SipServletContextEvent event) {
///         new SettingsManager<>(event, MyConfig.class, new MyConfigSample());
///     }
///
///     public void servletDestroyed(SipServletContextEvent event) {
///         SettingsManager.unregister(event);
///     }
///
///     public void callStarted(SipServletRequest outboundRequest) {
///         // Modify the outbound INVITE before it's sent to Bob
///         outboundRequest.setHeader("X-Custom", "value");
///     }
///
///     public void callAnswered(SipServletResponse outboundResponse) {
///         // Called when Bob answers — modify 200 OK before relaying to Alice
///     }
///
///     public void callConnected(SipServletRequest outboundRequest) {
///         // Called when ACK completes the three-way handshake
///     }
///
///     public void callCompleted(SipServletRequest outboundRequest) {
///         // Called when either party sends BYE
///     }
///
///     public void callDeclined(SipServletResponse outboundResponse) {
///         // Called when Bob rejects the call (4xx/5xx/6xx)
///     }
///
///     public void callAbandoned(SipServletRequest outboundRequest) {
///         // Called when Alice cancels before Bob answers
///     }
/// }
/// }
///
/// That's a complete B2BUA application. The framework provides the entire SIP state
/// machine; the developer only implements the callbacks they care about.
///
///
/// ## Call Lifecycle
///
/// The {@link B2buaListener} interface defines callbacks for every stage of a call.
/// {@link B2buaServlet} implements this interface, so your servlet subclass overrides
/// these methods directly:
///
/// <table>
///   <caption>B2BUA Call Lifecycle Callbacks</caption>
///   <tr><th>Callback</th><th>When It Fires</th><th>Typical Use</th></tr>
///   <tr>
///     <td>{@code callStarted(request)}</td>
///     <td>Outbound INVITE created, about to be sent to Bob</td>
///     <td>Add/modify headers, change Request-URI for routing</td>
///   </tr>
///   <tr>
///     <td>{@code callAnswered(response)}</td>
///     <td>Bob answered with 200 OK</td>
///     <td>Log CDR start, modify SDP, start recording</td>
///   </tr>
///   <tr>
///     <td>{@code callConnected(request)}</td>
///     <td>ACK sent to Bob&mdash;media is now flowing</td>
///     <td>Start timers, update presence</td>
///   </tr>
///   <tr>
///     <td>{@code callCompleted(request)}</td>
///     <td>BYE received from either party</td>
///     <td>Log CDR end, cleanup resources</td>
///   </tr>
///   <tr>
///     <td>{@code callDeclined(response)}</td>
///     <td>Bob rejected with 4xx/5xx/6xx</td>
///     <td>Try alternate routing, log failure reason</td>
///   </tr>
///   <tr>
///     <td>{@code callAbandoned(request)}</td>
///     <td>Alice sent CANCEL before Bob answered</td>
///     <td>Log abandoned call, cleanup</td>
///   </tr>
///   <tr>
///     <td>{@code requestEvent(request)}</td>
///     <td>Mid-dialog request (re-INVITE, INFO, UPDATE)</td>
///     <td>Modify headers or SDP before forwarding</td>
///   </tr>
///   <tr>
///     <td>{@code responseEvent(response)}</td>
///     <td>Mid-dialog response</td>
///     <td>Modify response before relaying</td>
///   </tr>
/// </table>
///
///
/// ## How Requests Are Routed
///
/// {@link B2buaServlet} overrides
/// {@link org.vorpal.blade.framework.v2.AsyncSipServlet#chooseCallflow chooseCallflow()}
/// to select the appropriate callflow for each incoming request:
///
/// <table>
///   <caption>Request Routing</caption>
///   <tr><th>Request</th><th>Callflow</th></tr>
///   <tr><td>Initial INVITE</td><td>{@link InitialInvite}</td></tr>
///   <tr><td>Re-INVITE (mid-dialog)</td><td>{@link Reinvite}</td></tr>
///   <tr><td>BYE or CANCEL</td><td>{@link Terminate}</td></tr>
///   <tr><td>Everything else (INFO, OPTIONS, UPDATE, ...)</td><td>{@link Passthru}</td></tr>
/// </table>
///
/// You can override {@code chooseCallflow()} in your servlet to substitute custom
/// callflows for any of these. For example, you might replace {@link InitialInvite}
/// with a custom callflow that performs number translation before creating the
/// outbound leg.
///
///
/// ## Controlling Message Processing
///
/// By default, each callflow automatically forwards messages between call legs.
/// To intercept and prevent automatic forwarding, call {@code doNotProcess()} from
/// within a lifecycle callback:
///
/// {@snippet :
/// public void callStarted(SipServletRequest outboundRequest) {
///     if (shouldBlock(outboundRequest)) {
///         // Reject the call with 403 Forbidden — don't forward to Bob
///         doNotProcess(outboundRequest, 403, "Blocked by policy");
///     }
/// }
/// }
///
/// The {@code doNotProcess()} method has three variants:
///
/// <ul>
///   <li>{@code doNotProcess(message)} &mdash; suppress forwarding silently</li>
///   <li>{@code doNotProcess(request, statusCode)} &mdash; suppress forwarding and
///       send an error response to the other leg</li>
///   <li>{@code doNotProcess(request, statusCode, reasonPhrase)} &mdash; same with
///       a custom reason phrase</li>
/// </ul>
///
///
/// ## Under the Hood: Callflow Classes
///
/// Each callflow class uses the lambda pattern described in the
/// {@linkplain org.vorpal.blade.framework.v2.callflow callflow package}:
///
/// <ul>
///   <li><b>{@link InitialInvite}</b> &mdash; creates the outbound INVITE leg,
///       links sessions, handles the full INVITE/response/ACK exchange with nested
///       lambdas, and fires lifecycle callbacks ({@code callStarted},
///       {@code callAnswered}, {@code callConnected}, {@code callDeclined})
///       at each stage.</li>
///   <li><b>{@link Reinvite}</b> &mdash; forwards re-INVITE between legs using the
///       three-step lambda pattern: forward request, relay response, forward ACK.
///       Fires {@code requestEvent} and {@code responseEvent} callbacks.</li>
///   <li><b>{@link Terminate}</b> &mdash; handles both BYE and CANCEL in a single
///       class. Inspects the linked session's dialog state (INITIAL, EARLY,
///       CONFIRMED, TERMINATED) to determine the correct termination action.
///       Fires {@code callCompleted} or {@code callAbandoned} as appropriate.</li>
///   <li><b>{@link Passthru}</b> &mdash; simple two-step forwarding for mid-dialog
///       methods that don't require ACK (INFO, OPTIONS, UPDATE, MESSAGE).
///       Fires {@code requestEvent} and {@code responseEvent} callbacks.</li>
/// </ul>
///
///
/// ## Core Classes
///
/// ### Servlet and Configuration
///
/// - {@link B2buaServlet} - Abstract base servlet with lifecycle callbacks and callflow routing
/// - {@link B2buaListener} - Serializable interface for call lifecycle events
/// - {@link B2buaConfiguration} - Base configuration class for B2BUA applications
///
/// ### Callflow Handlers
///
/// - {@link InitialInvite} - Initial INVITE: creates outbound leg, links sessions, full handshake
/// - {@link Reinvite} - Re-INVITE: three-step lambda pattern for mid-dialog modifications
/// - {@link Terminate} - BYE and CANCEL: state-aware termination of the other call leg
/// - {@link Passthru} - INFO, OPTIONS, UPDATE, etc.: simple bidirectional forwarding
///
/// @see B2buaServlet
/// @see B2buaListener
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
/// @see org.vorpal.blade.framework.v2.AsyncSipServlet
package org.vorpal.blade.framework.v2.b2bua;
