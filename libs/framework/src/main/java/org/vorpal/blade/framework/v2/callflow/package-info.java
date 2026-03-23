/// # SIP Callflow Framework
///
/// This package provides a comprehensive framework for building SIP application callflows
/// using asynchronous callback-based programming patterns. It enables developers to create
/// complex SIP applications with fluent APIs for handling requests, responses, timers,
/// and dialog management.
///
/// ## Core Architecture
///
/// The framework is built around the [Callflow] abstract base class which provides:
/// - Asynchronous callback registration for requests and responses
/// - Timer management with lambda function callbacks
/// - SIP message creation and manipulation utilities
/// - Proxy and B2BUA functionality
/// - Dialog linking and session management
/// - Error handling and race condition management
///
/// ## Key Classes
///
/// ### Base Classes
/// - [Callflow] - Abstract base class for all SIP callflows with comprehensive SIP utilities
/// - [ClientCallflow] - Base class for client-initiated callflows with no-op request processing
/// - [Callback] - Functional interface for serializable SIP callbacks extending `Consumer`
///
/// ### Pre-built Callflows
/// - [Callflow481] - Responds with 481 (Call/Transaction Does Not Exist)
/// - [CallflowResponseCode] - Configurable callflow for specific response codes
/// - [CallflowAckBye] - Handles CANCEL/200 OK race conditions by sending ACK then BYE
/// - [CallflowCallConnectedError] - Error handling for connected calls with proper cleanup
///
/// ### Utility Classes
/// - [Expectation] - Manages expected SIP method arrivals with callbacks and cleanup
///
/// ## Features
///
/// ### Asynchronous Processing
/// The framework uses callback-based handling for all SIP messages and timer events.
/// The [Callback] interface extends `Consumer` and supports serialization for cluster environments.
///
/// ### Dialog and Session Management
/// - Vorpal session IDs for unique identification across the system
/// - Dialog ID generation for tracking SIP dialogs
/// - Session linking for B2BUA scenarios
/// - Glare state management for race condition handling
///
/// ### Message Manipulation
/// - Copy headers, content, and create various SIP messages
/// - Create new requests from templates with routing directives
/// - Response creation with status code copying
/// - ACK/PRACK generation for proper dialog handling
///
/// ### Proxy Support
/// - Multi-endpoint proxying with parallel or sequential modes
/// - `ProxyPlan` support for tiered routing strategies
/// - Response callback handling for proxy scenarios
///
/// ### Timer Services
/// - One-time and periodic timer scheduling
/// - Millisecond precision timing
/// - Lambda callback integration
/// - Timer cancellation and cleanup
///
/// ### Error Handling
/// - Built-in error callflows for common scenarios
/// - Exception wrapping and propagation
/// - Proper cleanup for failed calls
/// - Connected call error handling with BYE termination
///
/// ## SIP Method Constants
///
/// The [Callflow] class provides constants for all standard SIP methods:
/// `INVITE`, `ACK`, `BYE`, `CANCEL`, `REGISTER`, `OPTIONS`, `PRACK`, `SUBSCRIBE`,
/// `NOTIFY`, `PUBLISH`, `INFO`, `UPDATE`, `REFER`, and `MESSAGE`.
///
/// ## Response Classification
///
/// Static utility methods are provided for response classification:
/// - `provisional()` - 1xx responses
/// - `successful()` - 2xx responses  
/// - `redirection()` - 3xx responses
/// - `failure()` - 4xx, 5xx, 6xx responses
///
/// @see Callflow
/// @see Callback
/// @see Expectation
/// @see ClientCallflow
package org.vorpal.blade.framework.v2.callflow;
