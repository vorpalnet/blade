/// This package provides comprehensive mock implementations of SIP servlet interfaces for unit testing
/// SIP applications without requiring a full SIP container environment. The mock classes enable
/// isolated testing of SIP application logic by providing functional implementations of core
/// SIP servlet APIs with in-memory storage and stub behaviors for container-specific operations.
///
/// ## Key Classes
///
/// - [DummyApplicationSession] - Mock implementation of `SipApplicationSession` with functional attribute storage, session management, and configurable expiration handling
/// - [DummyMessage] - Base mock implementation of `SipServletMessage` providing comprehensive message functionality including header management, attribute storage, and content handling
/// - [DummyRequest] - Mock implementation of `SipServletRequest` for testing SIP request handling with support for response creation, URI management, and servlet request operations
/// - [DummyResponse] - Mock implementation of `SipServletResponse` for testing SIP response handling with status code management, reason phrase mapping, and response-specific operations
/// - [DummySipSession] - Mock implementation of `SipSession` with functional attribute storage and basic request creation capabilities
///
/// ## Core Features
///
/// ### Functional Implementations
/// - **Attribute Management** - All mock classes provide fully functional `get`/`set`/`remove`/`clear` attribute operations for testing state management
/// - **Header Operations** - Complete header management including standard, address, and parameterable headers with proper storage and retrieval
/// - **Session Relationships** - Proper associations between application sessions, SIP sessions, and messages maintain realistic object hierarchies
/// - **Message Creation** - [DummyRequest] creates [DummyResponse] instances with appropriate status codes and [DummySipSession] creates [DummyRequest] instances for various SIP methods
///
/// ### Testing Support
/// - **Multiple Constructors** - Flexible construction options using strings, URIs, or Address objects to accommodate different testing scenarios  
/// - **No-op Operations** - Container-dependent methods like `send()`, proxy operations, and timer management are implemented as safe no-ops
/// - **Sensible Defaults** - Stub methods return appropriate default values (`null`, `false`, `0`) rather than throwing exceptions
/// - **Status Code Mapping** - [DummyResponse.ReasonPhrase] utility provides standard SIP reason phrases for all valid status code ranges
///
/// ## Implementation Architecture
///
/// The mock implementations use simple in-memory data structures for all storage operations:
/// - `HashMap` instances for attribute and header storage
/// - Direct field storage for message properties and session state
/// - Delegation patterns where [DummyResponse] methods delegate to associated request objects for consistency
///
/// Container services that require external dependencies (network operations, container lifecycle,
/// proxy functionality) are implemented as no-ops to ensure tests remain isolated and executable
/// without a SIP container runtime.
///
/// ## Usage Patterns
///
/// These mock classes support direct instantiation through provided constructors, allowing test
/// code to create realistic SIP message flows and session hierarchies. The functional attribute
/// and header storage enables verification of application state changes, while the stub implementations
/// prevent tests from failing due to missing container services.
///
/// Tests can construct complete request/response pairs, verify header manipulations, and
/// validate session attribute management without requiring actual SIP network infrastructure
/// or container deployment.
///
/// @see javax.servlet.sip.SipApplicationSession
/// @see javax.servlet.sip.SipServletMessage
/// @see javax.servlet.sip.SipServletRequest
/// @see javax.servlet.sip.SipServletResponse
/// @see javax.servlet.sip.SipSession
package org.vorpal.blade.framework.v2.testing;
