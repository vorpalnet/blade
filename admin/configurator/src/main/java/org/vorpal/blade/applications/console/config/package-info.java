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
/// - [FileManagerServlet] - The editor's WebSocket endpoint (`/websocket`). It reads and
///   writes JSON configs only inside the config directory, and lets only Admin and
///   Operator change anything; the handshake records the user's roles and refuses a
///   socket opened from another site.
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
/// ## Sub-packages
///
/// ### [org.vorpal.blade.applications.console.config.test]
/// Provides a web-based console and configuration management system for Blade
/// applications, including JMX integration through
/// [BladeConsoleMXBean][org.vorpal.blade.applications.console.config.test.BladeConsoleMXBean]
/// and [BladeConsole][org.vorpal.blade.applications.console.config.test.BladeConsole],
/// servlet lifecycle management via
/// [BladeConsoleListener][org.vorpal.blade.applications.console.config.test.BladeConsoleListener],
/// and file-based configuration utilities with
/// [ConfigHelper][org.vorpal.blade.applications.console.config.test.ConfigHelper].
/// Several EJB components are included in an inactive test state for development purposes.
///
/// ## Related Packages
///
/// The configurator module also contains the following packages outside this hierarchy:
///
/// ### [org.vorpal.blade.applications.console.webapp]
/// Provides web servlet components for the configurator application's user interface,
/// including the [Logout][org.vorpal.blade.applications.console.webapp.Logout] servlet
/// that handles session invalidation with anti-caching headers and redirect to the login page.
///
/// ### `org.vorpal.blade.framework.v2.config`
/// Contains the [SettingsMXBean][org.vorpal.blade.framework.v2.config.SettingsMXBean] JMX
/// interface used by the configurator to interact with remote service configurations at
/// runtime, providing streaming file I/O operations, configuration scoping
/// (domain/cluster/server), and runtime reload capabilities.
///
/// @see ConfigurationMonitor
/// @see FileManagerServlet
/// @see ConfigurationMonitorStartup
package org.vorpal.blade.applications.console.config;
