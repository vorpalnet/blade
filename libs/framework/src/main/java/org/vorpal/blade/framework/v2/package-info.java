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
/// ## Sub-packages
///
/// ### [org.vorpal.blade.framework.v2.analytics]
/// Provides comprehensive analytics and monitoring capabilities for SIP servlet applications.
/// The [Analytics][org.vorpal.blade.framework.v2.analytics.Analytics] engine configures event collection with JMS publishing support, while JPA
/// entities like [Event][org.vorpal.blade.framework.v2.analytics.Event], [Application][org.vorpal.blade.framework.v2.analytics.Application], and [Session][org.vorpal.blade.framework.v2.analytics.Session] enable database persistence of call
/// detail records and session lifecycle data.
///
/// ### [org.vorpal.blade.framework.v2.b2bua]
/// Implements Back-to-Back User Agent functionality through [B2buaServlet][org.vorpal.blade.framework.v2.b2bua.B2buaServlet] and specialized
/// callflow handlers including [InitialInvite][org.vorpal.blade.framework.v2.b2bua.InitialInvite], [Reinvite][org.vorpal.blade.framework.v2.b2bua.Reinvite], [Terminate][org.vorpal.blade.framework.v2.b2bua.Terminate], and [Passthru][org.vorpal.blade.framework.v2.b2bua.Passthru].
/// Applications extend [B2buaServlet][org.vorpal.blade.framework.v2.b2bua.B2buaServlet] and implement [B2buaListener][org.vorpal.blade.framework.v2.b2bua.B2buaListener] to customize call
/// handling with lifecycle callbacks for started, answered, connected, and completed states.
///
/// ### [org.vorpal.blade.framework.v2.callflow]
/// Provides the core callback-based programming model for building SIP application callflows.
/// The [Callflow][org.vorpal.blade.framework.v2.callflow.Callflow] abstract base class offers asynchronous callback registration, timer management,
/// proxy and B2BUA functionality, and dialog linking. Includes pre-built callflows like
/// [Callflow481][org.vorpal.blade.framework.v2.callflow.Callflow481] and [CallflowAckBye][org.vorpal.blade.framework.v2.callflow.CallflowAckBye] for common SIP scenarios.
///
/// ### [org.vorpal.blade.framework.v2.config]
/// Delivers comprehensive configuration management with translation maps, routing configurations,
/// attribute selectors, and JSON serialization for SIP-specific types. [SettingsManager][org.vorpal.blade.framework.v2.config.SettingsManager] handles
/// automatic JSON loading with domain/cluster/server config merging, while multiple
/// [TranslationsMap][org.vorpal.blade.framework.v2.config.TranslationsMap] implementations support exact, prefix, and CIDR-based lookups.
///
/// ### [org.vorpal.blade.framework.v2.keepalive]
/// Implements SIP session keep-alive functionality using re-INVITE requests to refresh RTP
/// streams and prevent session timeouts. [KeepAlive][org.vorpal.blade.framework.v2.keepalive.KeepAlive] performs three-step SDP exchange between
/// call legs, while [KeepAliveExpiry][org.vorpal.blade.framework.v2.keepalive.KeepAliveExpiry] terminates expired sessions by sending BYE requests
/// to both participants.
///
/// ### [org.vorpal.blade.framework.v2.logging]
/// Offers a SIP-aware logging framework with session-aware logging, ANSI color support, and
/// sequence diagram generation. [Logger][org.vorpal.blade.framework.v2.logging.Logger] provides specialized methods for SIP message tracing
/// and JSON serialization, while [LogManager][org.vorpal.blade.framework.v2.logging.LogManager] handles centralized configuration and lifecycle
/// management with servlet context integration.
///
/// ### [org.vorpal.blade.framework.v2.proxy]
/// Provides a framework for building SIP proxy applications with multi-tier routing and failover.
/// [ProxyServlet][org.vorpal.blade.framework.v2.proxy.ProxyServlet] serves as the base servlet, while [ProxyPlan][org.vorpal.blade.framework.v2.proxy.ProxyPlan] and [ProxyTier][org.vorpal.blade.framework.v2.proxy.ProxyTier] define
/// hierarchical routing strategies supporting both parallel and serial execution modes with
/// configurable timeouts.
///
/// ### [org.vorpal.blade.framework.v2.testing]
/// Contains mock implementations of SIP servlet interfaces for unit testing without a full
/// SIP container. Classes like [DummyRequest][org.vorpal.blade.framework.v2.testing.DummyRequest], [DummyResponse][org.vorpal.blade.framework.v2.testing.DummyResponse], [DummySipSession][org.vorpal.blade.framework.v2.testing.DummySipSession], and
/// [DummyApplicationSession][org.vorpal.blade.framework.v2.testing.DummyApplicationSession] provide functional attribute storage, header operations, and
/// session relationships while stubbing container-dependent operations as safe no-ops.
///
/// ### [org.vorpal.blade.framework.v2.transfer]
/// Implements SIP call transfer operations supporting blind, attended, conference, and
/// REFER-based transfer patterns. The [Transfer][org.vorpal.blade.framework.v2.transfer.Transfer] base class and [TransferInitialInvite][org.vorpal.blade.framework.v2.transfer.TransferInitialInvite]
/// handler integrate with B2BUA functionality, while [TransferSettings][org.vorpal.blade.framework.v2.transfer.TransferSettings] and [TransferListener][org.vorpal.blade.framework.v2.transfer.TransferListener]
/// provide configurable behavior and lifecycle event notifications.
///
/// ## Bundled Libraries
///
/// This module also bundles the following library packages that provide SDP parsing
/// and manipulation capabilities used by the framework:
///
/// ### [javax.sdp]
/// Provides a comprehensive Java API for parsing, creating, and manipulating Session
/// Description Protocol (SDP) messages as defined in RFC 2327. Includes the [SdpFactory][javax.sdp.SdpFactory]
/// for creating and parsing SDP objects, interfaces for all standard SDP fields ([SessionDescription][javax.sdp.SessionDescription],
/// [MediaDescription][javax.sdp.MediaDescription], [Connection][javax.sdp.Connection], [Attribute][javax.sdp.Attribute], etc.), and [SdpConstants][javax.sdp.SdpConstants] with RTP/AVP
/// payload type mappings for standard audio and video codecs.
///
/// ### [gov.nist.core]
/// Provides foundational core classes and utilities for the NIST SIP/SDP implementation.
/// Contains base classes [GenericObject][gov.nist.core.GenericObject] and [GenericObjectList][gov.nist.core.GenericObjectList] for protocol objects,
/// data structures like [NameValue][gov.nist.core.NameValue] and [NameValueList][gov.nist.core.NameValueList] for parameter handling, network
/// host representation via [Host][gov.nist.core.Host] and [HostPort][gov.nist.core.HostPort], and parsing infrastructure including
/// [LexerCore][gov.nist.core.LexerCore], [ParserCore][gov.nist.core.ParserCore], and [HostNameParser][gov.nist.core.HostNameParser] for tokenization and protocol parsing.
///
/// ### [gov.nist.javax.sdp]
/// Provides a concrete implementation of the SDP API based on JSR 141. Contains
/// [SessionDescriptionImpl][gov.nist.javax.sdp.SessionDescriptionImpl], [MediaDescriptionImpl][gov.nist.javax.sdp.MediaDescriptionImpl], and [TimeDescriptionImpl][gov.nist.javax.sdp.TimeDescriptionImpl] for
/// creating and manipulating SDP session descriptions with deep cloning support,
/// dynamic payload handling, and IMS precondition integration. Includes sub-packages
/// [gov.nist.javax.sdp.fields] for field-level implementations and
/// [gov.nist.javax.sdp.parser] for SDP message parsing.
///
/// @see AsyncSipServlet
package org.vorpal.blade.framework.v2;
