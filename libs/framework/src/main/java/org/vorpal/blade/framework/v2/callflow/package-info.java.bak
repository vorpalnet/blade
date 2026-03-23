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
/// - [Callback] - Functional interface for serializable SIP callbacks extending Consumer
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
/// - **Asynchronous Processing**: Callback-based handling of SIP messages and timers
/// - **Dialog Management**: Session linking, glare state handling, and dialog utilities
/// - **Message Manipulation**: Copy headers, content, and create various SIP messages
/// - **Proxy Support**: Advanced proxying with plans, tiers, and callback handling
/// - **Timer Services**: Flexible timer scheduling with lambda callbacks and cancellation
/// - **Error Handling**: Built-in error callflows and exception management
/// - **Analytics Integration**: Built-in logging and analytics support
/// - **Session Management**: Vorpal session IDs, dialog IDs, and keep-alive support
///
/// ## SIP Method Constants
///
/// The [Callflow] class provides constants for all standard SIP methods including
/// `INVITE`, `ACK`, `BYE`, `CANCEL`, `REGISTER`, `OPTIONS`, `PRACK`, `SUBSCRIBE`,
/// `NOTIFY`, `PUBLISH`, `INFO`, `UPDATE`, `REFER`, and `MESSAGE`.
///
/// @see Callflow
/// @see Callback
/// @see Expectation
/// @see ClientCallflow
package org.vorpal.blade.framework.v2.callflow;
