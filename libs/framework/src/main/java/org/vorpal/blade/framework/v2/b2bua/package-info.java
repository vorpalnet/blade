/// # B2BUA Framework
///
/// This package provides a comprehensive Back-to-back User Agent (B2BUA) framework for creating 
/// sophisticated SIP routing and proxy applications. The B2BUA pattern enables applications to 
/// act as both a SIP client and server, maintaining full control over call state and media flow.
///
/// ## Core Architecture
///
/// The framework implements a state-machine driven approach to handle SIP dialogs, providing 
/// robust call control capabilities including call establishment, modification, and termination.
///
/// ## Key Classes
///
/// ### Primary Components
/// - [B2buaServlet] - Base servlet implementing the B2BUA pattern and core routing logic
/// - [B2buaListener] - Callback interface for handling call lifecycle events and custom routing decisions
///
/// ### Call Flow Handlers
/// - [InitialInvite] - Processes initial INVITE requests and manages call establishment
/// - [Reinvite] - Handles mid-dialog re-INVITE requests for media modifications and session updates  
/// - [Terminate] - Manages call teardown through BYE and CANCEL requests
/// - [Passthru] - Provides transparent message forwarding for non-critical SIP messages
///
/// ### Legacy Components
/// - [ByeOld] - Legacy BYE request handler (deprecated in favor of [Terminate])
/// - [CancelOld] - Legacy CANCEL request handler (deprecated in favor of [Terminate])
///
/// ## Usage Example
///
/// ```java
/// public class MyB2buaServlet extends B2buaServlet {
///     
///     @Override
///     protected void servletCreated(ServletConfig config) {
///         // Configure routing logic
///         this.addB2buaListener(new MyCallListener());
///     }
///     
///     private class MyCallListener implements B2buaListener {
///         @Override
///         public void callStarted(InitialInvite invite) {
///             // Custom call routing logic
///             SipURI targetUri = createTargetUri(invite.getRequest());
///             invite.proxyTo(targetUri);
///         }
///     }
/// }
/// ```
///
/// ## Features
///
/// - **Stateful Call Control** - Maintains complete call state across SIP dialogs
/// - **Media Awareness** - Handles SDP negotiation and media parameter modifications
/// - **Flexible Routing** - Supports complex routing decisions based on call context
/// - **Error Handling** - Comprehensive error recovery and cleanup mechanisms
/// - **Event-Driven Architecture** - Extensible callback system for custom business logic
///
/// @since 2.0
/// @see B2buaServlet
/// @see B2buaListener
/// @see InitialInvite
package org.vorpal.blade.framework.v2.b2bua;