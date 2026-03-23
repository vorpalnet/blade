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
///   for file changes using `WatchService` and automatically updates managed 
///   MBeans when configurations change. Supports both single directory and recursive
///   monitoring with `preVisitDirectory` for directory traversal.
/// - [ConfigurationMonitorStartup] - Servlet context listener that initializes the
///   configuration monitoring system on application startup and handles cleanup on shutdown
///
/// ### Web Interface and Communication
///
/// - [FileManagerServlet] - Dual-purpose component that serves as both an HTTP servlet
///   (`/filemanager`) for file management operations and a WebSocket endpoint (`/websocket`)
///   for real-time bidirectional communication with web clients. Includes nested
///   [FileManagerServlet.Message] class for structured message exchange.
/// - [WebSocketFileManager] - Additional HTTP servlet mapped to `/filemanager/*` for
///   extended file management functionality
/// - [SaveDataServlet] - Handles POST requests to `/saveData` endpoint for persisting
///   configuration data to the filesystem
///
/// ## Key Features
///
/// ### Real-time File System Monitoring
/// The package uses [java.nio.file.WatchService] with standard watch event kinds
/// (`ENTRY_CREATE`, `ENTRY_MODIFY`, `ENTRY_DELETE`, `OVERFLOW`) to monitor configuration 
/// directories and automatically synchronizes changes with corresponding MBean instances.
///
/// ### WebSocket Communication
/// Provides bidirectional real-time communication through WebSocket endpoints with
/// session management (`onOpen`, `onClose`, `onMessage`, `onError`) and structured
/// messaging using the [FileManagerServlet.Message] class with type, content, and
/// timestamp fields.
///
/// ### MBean Integration
/// Automatically discovers and updates [org.vorpal.blade.framework.v2.config.SettingsMXBean] 
/// instances through JMX when configuration files are modified via the `updateManagedMBeans`
/// method, providing seamless runtime configuration updates.
///
/// ### HTTP File Operations
/// Supports standard HTTP GET and POST operations for file reading, writing, and management
/// through multiple servlet endpoints, with JSON-based data exchange using Jackson
/// `ObjectMapper` for configuration content processing.
///
/// ### Application Discovery
/// Includes functionality to query and discover available applications through the
/// `queryApps` method using JNDI lookups for dynamic configuration management.
///
/// @see ConfigurationMonitor
/// @see FileManagerServlet
/// @see ConfigurationMonitorStartup
/// @see SaveDataServlet
/// @see WebSocketFileManager
package org.vorpal.blade.applications.console.config;
