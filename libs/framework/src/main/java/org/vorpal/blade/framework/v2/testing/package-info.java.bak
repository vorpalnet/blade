/// # Testing Framework Package
///
/// This package provides mock implementations of SIP servlet interfaces for unit testing
/// SIP applications without requiring a full SIP container environment.
///
/// ## Key Classes
///
/// - [DummyApplicationSession] - Mock implementation of [javax.servlet.sip.SipApplicationSession] with functional attribute storage and session management
/// - [DummyMessage] - Base mock implementation of [javax.servlet.sip.SipServletMessage] providing common message functionality including header and attribute management
/// - [DummyRequest] - Mock implementation of [javax.servlet.sip.SipServletRequest] for testing SIP request handling with support for response creation
/// - [DummyResponse] - Mock implementation of [javax.servlet.sip.SipServletResponse] for testing SIP response handling with status code and reason phrase support
/// - [DummySipSession] - Mock implementation of [javax.servlet.sip.SipSession] with functional attribute storage and basic request creation
///
/// ## Features
///
/// All mock classes provide:
/// - **Functional attribute storage** - `get`/`set`/`remove`/`clear` operations work as expected for testing
/// - **Header management** - Basic header operations for messages including parameterable and address headers
/// - **Session relationships** - Proper linking between application sessions, SIP sessions, and messages
/// - **Response creation** - [DummyRequest] can create [DummyResponse] instances with appropriate status codes
/// - **Request creation** - [DummySipSession] can create [DummyRequest] instances for different SIP methods
/// - **Stub implementations** - Container-specific methods return sensible defaults, `null`, or perform no operations
///
/// ## Implementation Details
///
/// The mock implementations use simple in-memory storage for attributes and basic data structures
/// for headers and session state. Methods that would require actual SIP container services
/// (such as `send()`, proxy operations, or timer management) are implemented as no-ops or
/// return stub values to allow unit tests to run without external dependencies.
///
/// [DummyResponse] includes a `ReasonPhrase` utility class that maps standard SIP status codes
/// to their corresponding reason phrases. Most getter methods in [DummyResponse] delegate to
/// the associated request object to maintain consistency.
///
/// ## Usage Pattern
///
/// These mock classes are designed to be lightweight alternatives to actual SIP servlet objects,
/// allowing unit tests to focus on application logic without the overhead of a SIP container.
/// Tests can create instances directly using the provided constructors and verify behavior
/// through the functional attribute and header storage mechanisms.
///
/// @see javax.servlet.sip.SipApplicationSession
/// @see javax.servlet.sip.SipServletMessage
/// @see javax.servlet.sip.SipServletRequest
/// @see javax.servlet.sip.SipServletResponse
/// @see javax.servlet.sip.SipSession
package org.vorpal.blade.framework.v2.testing;
