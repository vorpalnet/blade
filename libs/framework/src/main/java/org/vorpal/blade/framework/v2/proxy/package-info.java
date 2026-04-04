/// SIP proxy framework with multi-tier routing, parallel/serial forking,
/// and automatic failover.
///
///
/// ## What is a SIP Proxy?
///
/// Unlike a B2BUA (which terminates and recreates SIP dialogs), a proxy forwards
/// requests on behalf of the caller without creating new dialogs. This is lighter
/// weight but gives less control over the media path. Proxies are typically used
/// for routing, load balancing, and registration.
///
///
/// ## Quick Start
///
/// Extend {@link ProxyServlet} and implement the {@link ProxyListener} callbacks
/// to build routing plans dynamically:
///
/// {@snippet :
/// public class MyProxy extends ProxyServlet {
///
///     public void proxyRequest(SipServletRequest request, ProxyPlan plan) {
///         // Build the routing plan for this request
///         ProxyTier tier = new ProxyTier();
///         tier.setMode(ProxyTier.Mode.parallel);
///         tier.setTimeout(5);
///         tier.addEndpoint(sipFactory.createURI("sip:server1@backend"));
///         tier.addEndpoint(sipFactory.createURI("sip:server2@backend"));
///         plan.getTiers().add(tier);
///     }
///
///     public void proxyResponse(SipServletResponse response, ProxyPlan plan) {
///         // Called when a response arrives from a proxied destination
///     }
/// }
/// }
///
///
/// ## Routing Plans and Tiers
///
/// A {@link ProxyPlan} contains an ordered list of {@link ProxyTier} objects.
/// Each tier represents a group of endpoints to try, with a mode and timeout:
///
/// <table>
///   <caption>Tier Modes</caption>
///   <tr><th>Mode</th><th>Behavior</th></tr>
///   <tr>
///     <td>{@code parallel}</td>
///     <td>All endpoints in the tier are tried simultaneously &mdash; the first
///         successful response wins</td>
///   </tr>
///   <tr>
///     <td>{@code serial}</td>
///     <td>Endpoints are tried one at a time in order &mdash; the next is tried
///         only if the previous fails or times out</td>
///   </tr>
/// </table>
///
/// Tiers are tried sequentially: if all endpoints in the first tier fail, the
/// framework automatically moves to the next tier. If all tiers are exhausted,
/// a 502 Bad Gateway response is sent upstream.
///
/// ### Example: Two-Tier Routing
///
/// <pre>{@code
/// Tier 1 (parallel, 5s):  server1, server2    ← try both at once
/// Tier 2 (serial, 10s):   backup1, backup2    ← fall back to backups in order
/// }</pre>
///
///
/// ## How Requests Are Routed
///
/// {@link ProxyServlet} overrides {@code chooseCallflow()} to select:
///
/// <ul>
///   <li><b>Initial INVITE</b> &rarr; {@link ProxyInvite} &mdash; executes the
///       routing plan by calling {@code proxyRequest()} to build the plan, then
///       proxying through each tier with automatic failover</li>
///   <li><b>CANCEL</b> &rarr; {@link ProxyCancel} &mdash; the SIP container handles
///       CANCEL automatically; this class exists for consistency</li>
/// </ul>
///
///
/// ## Core Classes
///
/// - {@link ProxyServlet} - Abstract base servlet with {@link ProxyListener} integration
/// - {@link ProxyListener} - Callback interface: {@code proxyRequest()} to build plans, {@code proxyResponse()} for responses
/// - {@link ProxyPlan} - Ordered list of tiers with an optional context object for application data
/// - {@link ProxyTier} - A group of endpoints with a mode (parallel/serial) and timeout
/// - {@link ProxyInvite} - Callflow that executes the routing plan with tier-by-tier failover
/// - {@link ProxyCancel} - No-op callflow for CANCEL (container handles it)
///
/// @see ProxyServlet
/// @see ProxyListener
/// @see ProxyPlan
/// @see ProxyTier
package org.vorpal.blade.framework.v2.proxy;
