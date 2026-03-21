/// # Callflow Framework Package
///
/// Provides a powerful callflow framework for building SIP applications using Java lambda expressions
/// and functional programming paradigms. This framework simplifies SIP application development by
/// offering a declarative approach to handling SIP messages and call states.
///
/// ## Core Components
///
/// - [Callflow] - Base class providing comprehensive request/response handling utilities with 78 methods
///   for managing SIP message flows, session state, and callback registration
/// - [Callback] - Functional interface enabling lambda-based asynchronous callbacks for clean,
///   reactive programming patterns
/// - [ClientCallflow] - Specialized base class for client-initiated (UAC) callflows, handling
///   outbound call scenarios
/// - [Expectation] - Manages expected SIP method callbacks on sessions, providing predictable
///   message handling workflows
///
/// ## Additional Classes
///
/// - [Callflow481] - Handles specific 481 Call/Transaction Does Not Exist responses
/// - [CallflowAckBye] - Manages ACK and BYE message sequences in call termination scenarios
/// - [CallflowCallConnectedError] - Specialized error handling for connected call states
/// - [CallflowResponseCode] - Utility for managing and categorizing SIP response codes
///
/// ## Usage Example
///
/// ```java
/// public class MyCallflow extends Callflow {
///     public void start() {
///         // Handle incoming INVITE with lambda
///         expectRequest("INVITE", (request, session) -> {
///             sendResponse(200, "OK");
///             expectRequest("ACK", this::handleAck);
///         });
///     }
///     
///     private void handleAck(SipServletRequest request, SipSession session) {
///         // Call is now established
///         expectRequest("BYE", this::handleBye);
///     }
/// }
/// ```
///
/// This framework promotes clean separation of concerns and enables developers to build
/// robust SIP applications with minimal boilerplate code while maintaining full control
/// over call flow logic.
///
/// @since 2.0
/// @see Callflow
/// @see Callback
/// @see ClientCallflow
/// @see Expectation
package org.vorpal.blade.framework.v2.callflow;