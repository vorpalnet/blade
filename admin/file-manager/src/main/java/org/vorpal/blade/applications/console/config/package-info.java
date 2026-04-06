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
/// The console module also contains the following packages outside this hierarchy:
///
/// ### [org.vorpal.blade.applications.console.webapp]
/// Provides web servlet components for the console application's user interface,
/// including the [Logout][org.vorpal.blade.applications.console.webapp.Logout] servlet
/// that handles session invalidation with anti-caching headers and redirect to the login page.
///
/// ### [org.vorpal.blade.applications.console.mxgraph]
/// Provides utilities and servlets for integrating with mxGraph diagramming functionality.
/// Includes [OpenServlet][org.vorpal.blade.applications.console.mxgraph.OpenServlet] for
/// file upload and diagram format processing (Gliffy, GraphML, PNG with embedded XML),
/// [SaveServlet][org.vorpal.blade.applications.console.mxgraph.SaveServlet] for diagram
/// export, and [Formatter][org.vorpal.blade.applications.console.mxgraph.Formatter] for
/// XML pretty-printing and transformation.
///
/// ### [com.mxgraph.util]
/// Bundled utility classes for the mxGraph library including
/// [mxBase64][com.mxgraph.util.mxBase64] for high-performance BASE64 encoding/decoding,
/// [Utils][com.mxgraph.util.Utils] for compression/decompression and geometric transformations,
/// and [Constants][com.mxgraph.util.Constants] for application-wide size limits and configuration.
///
/// ### [org.vorpal.blade.framework.v2.config]
/// Contains the [SettingsMXBean][org.vorpal.blade.framework.v2.config.SettingsMXBean] JMX
/// interface used by the console to interact with remote service configurations at runtime,
/// providing streaming file I/O operations, configuration scoping (domain/cluster/server),
/// and runtime reload capabilities.
///
/// @see ConfigurationMonitor
/// @see ConfigurationMonitorStartup
/// @see FileManagement
/// @see FileManagerServlet
/// @see WebSocketFileManager
/// @see SaveDataServlet
/// @see RestAPI
package org.vorpal.blade.applications.console.config;
