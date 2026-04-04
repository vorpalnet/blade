/// Back-to-Back User Agent (B2BUA) implementation built on BLADE's lambda callflow pattern.
///
/// A B2BUA sits between two call legs (Alice and Bob), terminating the inbound call and
/// creating a corresponding outbound call. This enables call routing, header manipulation,
/// SDP modification, and call control — all expressed as readable lambda-based callflows.
///
/// ## How It Works
///
/// Extend [B2buaServlet] and implement [B2buaListener] to intercept messages at every
/// lifecycle point. The framework handles session linking, message forwarding, and state
/// management automatically. Your code focuses on business logic:
///
/// ```java
/// public class MyServlet extends B2buaServlet {
///     public void callStarted(SipServletRequest outboundRequest) {
///         // Modify the INVITE before it's sent to Bob
///         outboundRequest.setHeader("X-Custom", "value");
///     }
///     public void callAnswered(SipServletResponse outboundResponse) {
///         // Modify the 200 OK before it's sent back to Alice
///     }
/// }
/// ```
///
/// Under the hood, each SIP message type is handled by a specialized callflow class
/// that uses the lambda pattern. For example, [InitialInvite] expresses the entire
/// INVITE/response/ACK exchange in a single `process()` method with nested lambdas.
/// The callflow state — including references to both Alice and Bob's requests — is
/// automatically serialized into SIP session memory for distributed cluster failover.
///
/// ## Core Components
///
/// ### Main Classes
///
/// - [B2buaServlet] - Abstract base servlet that implements the core B2BUA logic and provides lifecycle callbacks
/// - [B2buaListener] - Interface for handling B2BUA call lifecycle events and message modification
/// - [B2buaConfiguration] - Configuration class extending the base Configuration for B2BUA-specific settings
///
/// ### Callflow Handlers
///
/// Each SIP message type has a dedicated callflow, all using the lambda pattern:
///
/// - [InitialInvite] - Handles initial INVITE requests, creates outbound leg and links sessions
/// - [Reinvite] - Processes re-INVITE requests for mid-call modifications with SDP exchange
/// - [Terminate] - Unified handler for BYE and CANCEL requests to terminate both call legs
/// - [Passthru] - Forwards mid-dialog requests (INFO, OPTIONS, etc.) between call legs
///
/// ### Legacy Components
///
/// Several deprecated classes are maintained for backward compatibility:
///
/// - [Bye] - Deprecated BYE handler, replaced by [Terminate]
/// - [Cancel] - Deprecated CANCEL handler, replaced by [Terminate]
/// - [ByeOld] - Legacy BYE implementation with complex glare handling
/// - [CancelOld] - Legacy CANCEL implementation with detailed sequence documentation
///
/// ## Architecture
///
/// The B2BUA framework follows a callflow-based architecture where different SIP message types are 
/// handled by specific callflow classes. The [B2buaServlet] serves as the main entry point, routing 
/// requests to appropriate callflows through the `chooseCallflow()` method and providing lifecycle 
/// callbacks through the [B2buaListener] interface.
///
/// Applications extend [B2buaServlet] and implement the [B2buaListener] methods to customize call handling,
/// modify messages, and implement routing logic. The framework automatically manages session linking,
/// message forwarding, and call state transitions.
///
/// ## Call Lifecycle Events
///
/// The [B2buaListener] interface provides callback methods for key call lifecycle events:
///
/// - `callStarted()` - Called when an outbound INVITE is about to be sent
/// - `callAnswered()` - Called when the outbound call receives a 200 OK response
/// - `callConnected()` - Called when the ACK for the initial INVITE is received
/// - `callCompleted()` - Called when a BYE request is received
/// - `callDeclined()` - Called when the outbound call receives an error response
/// - `callAbandoned()` - Called when a CANCEL request is received
/// - `requestEvent()` - Called for mid-dialog requests
/// - `responseEvent()` - Called for mid-dialog responses
///
/// ## Message Processing Control
///
/// The framework provides methods to control message processing:
///
/// - `doNotProcess()` - Prevents automatic message forwarding, allowing custom handling
/// - `getIncomingRequest()` - Retrieves the original incoming request for custom processing
///
/// @see org.vorpal.blade.framework.v2.AsyncSipServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
/// @see org.vorpal.blade.framework.v2.config.Configuration
package org.vorpal.blade.framework.v2.b2bua;
