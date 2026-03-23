/// # Blade Console Configuration Test Package
///
/// This package provides a management console interface and configuration utilities 
/// for testing and managing Blade framework applications. It includes JMX-based 
/// management beans, servlet lifecycle listeners, and file-based configuration helpers.
///
/// ## Core Management Components
///
/// - [BladeConsoleMXBean] - JMX management interface providing JSON configuration operations including retrieval, sample setting, and schema validation
/// - [BladeConsole] - Concrete implementation of the management bean that handles configuration access through `getJson()`, `setSampleJson()`, and `setJsonSchema()` methods
/// - [BladeConsoleListener] - Servlet context listener annotated with `@WebListener` that manages console lifecycle during application startup and shutdown
///
/// ## Configuration Utilities
///
/// - [ConfigHelper] - Comprehensive utility class for file-based configuration management supporting multiple configuration types, local file operations, JMX integration with `SettingsMXBean`, and directory listing capabilities
///
/// ## Test Components
///
/// - [HelloWorld] - Remote EJB interface for testing distributed functionality
/// - [HelloWorldBean] - Stateless session bean implementation with `getHelloWorld()` method for testing EJB container integration
/// - [HelloBean] - Additional service bean providing `sayHelloFromServiceBean()` method for testing purposes
///
/// The package integrates with the broader Blade framework through JMX management
/// interfaces using `MBeanServer` and `ObjectName` for runtime configuration access.
/// Configuration data is handled in JSON format with file I/O operations managed
/// through NIO.2 APIs. The [ConfigHelper] class provides both programmatic file access 
/// and integration with the Blade framework's settings management system through
/// [org.vorpal.blade.framework.v2.config.SettingsMXBean].
///
/// @see BladeConsoleMXBean
/// @see BladeConsole
/// @see ConfigHelper
/// @see BladeConsoleListener
package org.vorpal.blade.applications.console.config.test;
