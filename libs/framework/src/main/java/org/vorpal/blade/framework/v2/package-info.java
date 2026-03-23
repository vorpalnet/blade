/// # Vorpal Blade Framework v2 - Asynchronous SIP Servlet Framework
///
/// This package provides a comprehensive framework for building asynchronous SIP servlets with
/// lambda expression support, designed for high-performance telecommunications applications.
/// The framework implements advanced SIP call control patterns including B2BUA (Back-to-Back
/// User Agent) functionality with sophisticated session management and error recovery.
///
/// ## Core Components
///
/// ### Main Framework Class
///
/// - [AsyncSipServlet] - Abstract base servlet providing asynchronous SIP message processing
///   with lambda-based callback handling, glare detection, session linking, and analytics integration
///
/// ## Key Features
///
/// - **Asynchronous Processing**: Lambda-based callbacks for non-blocking SIP message handling using [org.vorpal.blade.framework.v2.callflow.Callback] objects
/// - **Glare Prevention**: Automatic queuing of conflicting requests to prevent 491 responses on UDP transport
/// - **Session Management**: Sophisticated session linking and attribute extraction using configurable [org.vorpal.blade.framework.v2.config.AttributeSelector] objects
/// - **Analytics Integration**: Optional JMS-based event publishing for call detail records and monitoring through [org.vorpal.blade.framework.v2.analytics.Analytics] framework
/// - **Error Recovery**: Automatic upstream error notification and downstream call termination
/// - **Hash-based Session Correlation**: MD5-based application session keying with collision detection via `getAppSessionHashKey()`
///
/// ## Implementation Pattern
///
/// Applications extend [AsyncSipServlet] and implement three abstract methods:
/// - `servletCreated(SipServletContextEvent)` - Application-specific initialization
/// - `servletDestroyed(SipServletContextEvent)` - Cleanup and resource release
/// - `chooseCallflow(SipServletRequest)` - [org.vorpal.blade.framework.v2.callflow.Callflow] selection based on request characteristics
///
/// The framework handles SIP message routing through `doRequest()` and `doResponse()` methods,
/// callback management via servlet timers, and session lifecycle automatically, allowing
/// applications to focus on business logic implementation.
///
/// ## Static Utilities
///
/// The framework provides several utility methods for common SIP operations:
/// - `hash(String)` - MD5 hashing for session key generation
/// - `getAccountName(Address)` and `getAccountName(URI)` - Extract user@host format account names
/// - `sendResponse(SipServletResponse)` - Centralized response transmission with logging
/// - `isProxy(SipServletMessage)` - Detect proxy mode operation
/// - Vorpal header extraction methods for session correlation
///
/// ## Integration Points
///
/// This package integrates with several companion packages:
/// - `analytics` - Event publishing and call detail record generation via [org.vorpal.blade.framework.v2.analytics.Event] objects
/// - `callflow` - Callback-based call flow processing patterns through [org.vorpal.blade.framework.v2.callflow.Callflow] implementations
/// - `config` - Session parameter extraction using [org.vorpal.blade.framework.v2.config.SessionParameters] and [org.vorpal.blade.framework.v2.config.AttributeSelector]
/// - `logging` - Centralized logging with SIP message tracing via [org.vorpal.blade.framework.v2.logging.Logger]
/// - `b2bua` - Back-to-Back User Agent implementation patterns including [org.vorpal.blade.framework.v2.b2bua.Terminate] functionality
///
/// The framework is designed for deployment in SIP servlet containers implementing the
/// JSR 289 specification and provides both servlet context and SIP session lifecycle
/// management with comprehensive error handling and recovery mechanisms.
///
/// @see AsyncSipServlet
package org.vorpal.blade.framework.v2;
