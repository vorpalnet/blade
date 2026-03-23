/// # Analytics Framework Package
///
/// This package provides comprehensive analytics and monitoring capabilities for SIP servlet applications
/// within the Vorpal Blade framework. It enables collection, processing, and publishing of application
/// events, session data, and custom attributes for analysis and reporting purposes.
///
/// ## Core Components
///
/// ### Analytics Engine
/// - [Analytics] - Main analytics engine that configures event collection and attribute selection with JMS publishing support
/// - [AnalyticsFilter] - Servlet filter for intercepting HTTP requests and responses to capture analytics data
/// - [EventSelector] - Configures which events and attributes to collect based on selectors
///
/// ### Specialized Analytics Implementations
/// - [AnalyticsAsyncSipServletSample] - Analytics implementation for asynchronous SIP servlet scenarios
/// - [AnalyticsB2buaSample] - Back-to-back user agent analytics with dialog type support, extends async implementation
/// - [AnalyticsTransferSample] - Specialized analytics for call transfer scenarios, extends B2BUA implementation
///
/// ### Data Model
/// - [Application] - JPA entity representing SIP application lifecycle information with deployment details
/// - [Event] - JPA entity for storing analytics events with associated attributes and JSON serialization support
/// - [Attribute] - JPA entity for key-value attribute data linked to events
/// - [Session] - JPA entity tracking SIP application session lifecycle with timestamps
/// - [SessionKey] - JPA entity for session-specific key-value pairs
///
/// ### Primary Key Classes
/// - [AttributePK] - Composite primary key for attribute entities combining event ID and name
/// - [SessionKeyPK] - Composite primary key for session key entities with session ID, name, and value
///
/// ### Message Publishing
/// - [JmsPublisher] - JMS-based publisher for sending analytics data to message queues with lifecycle management
///
/// ### HTTP Request/Response Handling
/// - [BufferedRequestWrapper] - HTTP request wrapper that buffers request body for analytics without consuming the stream
/// - [BufferedResponseWrapper] - HTTP response wrapper that buffers response body for analytics with custom servlet output stream
///
/// ## Features
///
/// - **Event Collection**: Configurable collection of SIP and HTTP events with thread-local request context
/// - **Attribute Extraction**: Pattern-based extraction of attributes from SIP messages and HTTP requests using `AttributeSelector`
/// - **Database Persistence**: JPA-based persistence of analytics data with named queries for all entity types
/// - **JMS Integration**: Asynchronous publishing of events to JMS queues with proper connection management and lifecycle support
/// - **Request Buffering**: Non-intrusive capture of HTTP request and response bodies with wrapper classes that preserve original streams
/// - **Session Tracking**: Complete lifecycle tracking of SIP application sessions with key-value storage capabilities
/// - **Async Support**: Full support for asynchronous servlet processing with proper listener integration through [AnalyticsFilter]
///
/// @see [org.vorpal.blade.framework.v2.config.AttributeSelector]
/// @see [org.vorpal.blade.framework.v2.callflow.Callflow]
/// @see [org.vorpal.blade.framework.v2.config.SettingsManager]
package org.vorpal.blade.framework.v2.analytics;
