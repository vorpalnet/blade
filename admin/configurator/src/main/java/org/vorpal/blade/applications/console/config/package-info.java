/// # Configuration Management Console
///
/// This package provides a web-based configuration management system for monitoring
/// and managing configuration files and MBean settings. It implements a real-time
/// file monitoring system with WebSocket-based communication for live updates.
///
/// ## Core Components
///
/// ### File System Monitoring
/// 
/// - [ConfigurationMonitor] - Background thread that watches configuration directories
///   for file changes using Java NIO.2 WatchService and automatically updates managed 
///   MBeans when configurations change
/// - [ConfigurationMonitorStartup] - Servlet context listener that initializes the
///   configuration monitoring system on application startup and handles cleanup on shutdown
///
/// ### Web Interface
///
/// - [FileManagerServlet] - Dual-purpose servlet that serves as both an HTTP servlet
///   for file management operations (GET/POST) and a WebSocket endpoint for real-time 
///   bidirectional communication with web clients
/// - [WebSocketFileManager] - HTTP servlet mapped to `/filemanager/*` for additional
///   file management functionality
/// - [SaveDataServlet] - Handles POST requests for saving configuration data to the filesystem
///
/// ## Features
///
/// ### Real-time File System Monitoring
/// The package uses [java.nio.file.WatchService] to monitor configuration directories
/// for file system events (create, modify, delete) and automatically synchronizes
/// changes with corresponding SettingsMXBean instances through JMX.
///
/// ### WebSocket Communication
/// Provides bidirectional communication between the web interface and server through
/// the `/websocket` endpoint, enabling real-time updates and notifications using the
/// [FileManagerServlet.Message] class for structured message exchange.
///
/// ### MBean Integration
/// Automatically discovers and updates [org.vorpal.blade.framework.v2.config.SettingsMXBean] 
/// instances when configuration files are modified, providing seamless integration with 
/// JMX management and runtime configuration updates.
///
/// ### HTTP File Operations
/// Supports standard HTTP GET and POST operations for file reading, writing, and management
/// through servlet endpoints, with JSON-based data exchange for configuration content.
///
/// @see ConfigurationMonitor
/// @see FileManagerServlet
/// @see ConfigurationMonitorStartup
/// @see SaveDataServlet
package org.vorpal.blade.applications.console.config;
