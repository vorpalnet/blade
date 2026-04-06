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
/// ### API and Filtering
/// - [ConfiguratorAPI] - REST API for configurator operations
/// - [CorsFilter] - Servlet filter for Cross-Origin Resource Sharing support
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
/// ## Sub-packages
///
/// ### [org.vorpal.blade.applications.console.config.test]
/// Provides a management console interface and configuration utilities for testing and
/// managing Blade framework applications. Includes JMX-based management through
/// [BladeConsoleMXBean][org.vorpal.blade.applications.console.config.test.BladeConsoleMXBean]
/// and [BladeConsole][org.vorpal.blade.applications.console.config.test.BladeConsole],
/// servlet lifecycle management via
/// [BladeConsoleListener][org.vorpal.blade.applications.console.config.test.BladeConsoleListener],
/// and file-based configuration handling with
/// [ConfigHelper][org.vorpal.blade.applications.console.config.test.ConfigHelper].
/// Also contains EJB test components for verifying container integration on AdminServer.
///
/// ## Related Packages
///
/// ### [org.vorpal.blade.applications.console.mxgraph]
/// Provides the [Formatter][org.vorpal.blade.applications.console.mxgraph.Formatter] utility
/// for XML pretty-printing and transformation of configuration files.
///
/// ### [org.vorpal.blade.framework.v2.config]
/// Contains the [SettingsMXBean][org.vorpal.blade.framework.v2.config.SettingsMXBean] JMX
/// interface used by the console to interact with remote service configurations at runtime,
/// providing streaming file I/O operations, configuration scoping (domain/cluster/server),
/// and runtime reload capabilities.
///
/// @see ConfigurationMonitor
/// @see ConfigurationMonitorStartup
/// @see ConfiguratorAPI
/// @see CorsFilter
/// @see FileManagement
/// @see FileManagerServlet
/// @see WebSocketFileManager
/// @see SaveDataServlet
package org.vorpal.blade.applications.console.config;
