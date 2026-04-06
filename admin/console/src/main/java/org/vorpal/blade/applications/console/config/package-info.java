/// This package provides configuration management capabilities for the Vorpal Blade
/// console application, including application discovery, REST APIs, and web-based
/// interfaces for configuration management.
///
/// ## Core Components
///
/// ### Application Discovery
/// - [AppDiscovery] - Discovers deployed BLADE applications by querying JMX MBeans
///   registered under the `vorpal.blade:Name=*,Type=Configuration` pattern, returning
///   discovered application names via `queryApps()`
///
/// ### API Integration
/// - [RestAPI] - OpenAPI-documented REST service at `api/v1` providing application
///   configuration reload capabilities with Swagger integration
///
/// ## Related Modules
///
/// The BLADE console is part of a suite of administration modules:
///
/// - **File Manager** (`admin/file-manager`) - Real-time configuration file monitoring,
///   WebSocket-based file management, and REST endpoints for file operations
/// - **Configurator** (`admin/configurator`) - Web-based configuration editor with
///   JSON Schema-driven form generation and JMX integration
/// - **Flow** (`admin/flow`) - Call flow diagram editor using mxGraph
/// - **Tuning** (`admin/tuning`) - Performance tuning interface
/// - **Explorer** (`admin/explorer`) - Configuration explorer interface
///
/// ## Related Packages
///
/// ### [org.vorpal.blade.applications.console.webapp]
/// Provides web servlet components for the console application's user interface,
/// including the [Logout][org.vorpal.blade.applications.console.webapp.Logout] servlet
/// that handles session invalidation with anti-caching headers and redirect to the login page.
///
/// ### [org.vorpal.blade.framework.v2.config]
/// Contains the [SettingsMXBean][org.vorpal.blade.framework.v2.config.SettingsMXBean] JMX
/// interface used by the console to interact with remote service configurations at runtime.
///
/// @see AppDiscovery
/// @see RestAPI
package org.vorpal.blade.applications.console.config;
