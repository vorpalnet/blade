/// # Configuration Management for Vorpal Blade Console
///
/// This package provides comprehensive configuration management capabilities for the Vorpal Blade
/// console application, including real-time file monitoring, REST APIs, WebSocket communication,
/// and web-based interfaces for configuration manipulation.
///
/// ## Core Components
///
/// ### Configuration Monitoring
/// - [ConfigurationMonitor] - A background thread that monitors configuration file changes
///   using Java NIO `WatchService` with support for recursive directory monitoring. Automatically
///   updates managed MBeans when configuration changes are detected through `updateManagedMBeans()`
/// - [ConfigurationMonitorStartup] - `ServletContextListener` that initializes and starts the
///   configuration monitor when the web application context is created
///
/// ### File Management Services
/// - [FileManagement] - JAX-RS resource providing REST endpoints at `/microservices` for
///   configuration file operations including `listFiles()`, `saveFile()`, and `openFile()` methods
/// - [FileManagerServlet] - Hybrid servlet and WebSocket endpoint that handles both HTTP requests
///   at `/filemanager` and WebSocket connections at `/websocket` for real-time file management
///   operations with message broadcasting capabilities
/// - [WebSocketFileManager] - Simple servlet mapped to `/filemanager/*` URLs providing file
///   manager web interface access
/// - [SaveDataServlet] - Dedicated servlet at `/saveData` for handling configuration data
///   persistence via POST requests
///
/// ### API Integration
/// - [RestAPI] - OpenAPI-documented REST service at `api/v1` providing application session
///   examination capabilities with Swagger integration and MediaHub API definitions
///
/// ## Key Features
///
/// - **Real-time Monitoring**: Automatic detection and processing of configuration file changes
///   using `WatchService` with support for recursive directory monitoring through `preVisitDirectory()`
/// - **WebSocket Support**: Bidirectional real-time communication for file management with
///   session management, message handling via `onMessage()`, and connection lifecycle management
/// - **REST Interface**: RESTful APIs for programmatic access to configuration operations
///   including file listing, saving, and retrieval with JAX-RS annotations
/// - **MBean Integration**: Automatic updates to JMX managed beans when configurations change
///   through the `updateManagedMBeans()` mechanism with application discovery via `queryApps()`
/// - **Multi-format Support**: Handles various configuration formats with JSON message processing
///   and XML configuration file management
///
/// ## Architecture
///
/// The package follows a layered architecture with file system monitoring at the core,
/// REST and WebSocket APIs for client interaction, and servlet-based web integration.
/// Configuration changes are propagated through the system using JMX MBean notifications
/// and WebSocket message broadcasting for real-time updates.
///
/// The monitoring system uses Java NIO file watching capabilities to detect filesystem
/// changes and automatically update corresponding configuration objects. Web-based
/// interfaces provide both traditional HTTP and modern WebSocket communication channels
/// for interactive configuration management with message-based communication protocols.
///
/// @see ConfigurationMonitor
/// @see ConfigurationMonitorStartup
/// @see FileManagement
/// @see FileManagerServlet
/// @see WebSocketFileManager
/// @see SaveDataServlet
/// @see RestAPI
package org.vorpal.blade.applications.console.config;
