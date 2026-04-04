/// # Configuration Management for Vorpal Blade Console
///
/// This package provides comprehensive configuration management capabilities for the Vorpal Blade
/// console application, including file monitoring, REST APIs, and web-based interfaces for
/// configuration manipulation.
///
/// ## Core Components
///
/// ### Configuration Monitoring
/// - [ConfigurationMonitor] - A background thread that monitors configuration file changes
///   using Java NIO WatchService and automatically updates managed MBeans when changes occur
/// - [ConfigurationMonitorStartup] - ServletContextListener that initializes the configuration
///   monitor when the web application starts
///
/// ### File Management Services
/// - [FileManagement] - JAX-RS resource providing REST endpoints for microservices configuration
///   file operations including `listFiles()`, `saveFile()`, and `openFile()` methods
/// - [FileManagerServlet] - Hybrid servlet and WebSocket endpoint that handles both HTTP requests
///   and real-time WebSocket communication for file management operations with message broadcasting
/// - [WebSocketFileManager] - Simple servlet mapped to `/filemanager/*` URLs for file manager access
/// - [SaveDataServlet] - Dedicated servlet for handling configuration data persistence via POST requests
///
/// ### API Integration
/// - [RestAPI] - OpenAPI-documented REST service providing application session examination
///   capabilities with Swagger integration and MediaHub API definitions
///
/// ## Key Features
///
/// - **Real-time Monitoring**: Automatic detection and processing of configuration file changes
///   using WatchService with support for recursive directory monitoring
/// - **WebSocket Support**: Real-time bidirectional communication for file management operations
///   with session management and message broadcasting capabilities
/// - **REST Interface**: RESTful APIs for programmatic access to configuration operations
///   including file listing, saving, and retrieval
/// - **MBean Integration**: Automatic updates to JMX managed beans when configurations change
///   through the `updateManagedMBeans()` mechanism
/// - **Multi-format Support**: Handles various configuration formats including XML and JSON
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
/// for interactive configuration management.
///
/// @see ConfigurationMonitor
/// @see FileManagement
/// @see FileManagerServlet
/// @see RestAPI
/// @see ConfigurationMonitorStartup
package org.vorpal.blade.applications.console.config;
