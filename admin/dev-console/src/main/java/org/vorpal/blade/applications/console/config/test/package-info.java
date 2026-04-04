/// This package provides a management console interface and configuration utilities
/// for testing and managing Blade framework applications. It includes JMX-based
/// management beans, servlet lifecycle listeners, and file-based configuration helpers.
///
/// ## Core Management Components
///
/// - [BladeConsoleMXBean] - JMX management interface providing JSON configuration operations
/// - [BladeConsole] - Concrete implementation of the management bean
/// - [BladeConsoleListener] - Servlet context listener that manages console lifecycle
///
/// ## Configuration Utilities
///
/// - [ConfigHelper] - File-based configuration management with JMX integration
///
/// ## Test Components
///
/// - [HelloWorld] - Remote EJB interface for testing distributed functionality
/// - [HelloWorldBean] - Stateless session bean for testing EJB container integration
/// - [HelloBean] - Service bean for basic connectivity testing
///
/// ## JMX Management Interface
///
/// ### BladeConsoleMXBean Operations
/// The [BladeConsoleMXBean] interface defines three JSON configuration operations:
/// - `getJson(contextName)` - retrieves JSON configuration for a named application context
/// - `setSampleJson(contextName, json)` - stores a sample JSON configuration
/// - `setJsonSchema(contextName, jschema)` - sets the JSON schema for validation
///
/// ### BladeConsole Implementation
/// [BladeConsole] implements [BladeConsoleMXBean] and provides the concrete logic
/// for each operation. Currently serves as a test stub that logs invocations to
/// standard output.
///
/// ### BladeConsoleListener Lifecycle
/// The [BladeConsoleListener] is annotated with `@WebListener` and manages the
/// console MBean lifecycle. On `contextInitialized`, it can register the
/// [BladeConsole] MBean with the platform `MBeanServer` under the ObjectName
/// `vorpal.blade:Name=blade,Type=Configuration`. On `contextDestroyed`, it
/// unregisters the MBean. Also includes scaffolding for an embedded HSQLDB server.
///
/// ## Configuration File Management
///
/// ### ConfigHelper File Paths
/// The [ConfigHelper] class manages five configuration file paths rooted under
/// `config/custom/vorpal/`:
/// - **domain** - `<app>.json` for domain-scoped configuration
/// - **cluster** - `_clusters/<app>.json` for cluster-scoped configuration
/// - **server** - `_servers/<app>.json` for server-scoped configuration
/// - **schema** - `_schemas/<app>.jschema` for JSON schema definitions
/// - **sample** - `_samples/<app>.json.SAMPLE` for sample configurations
///
/// ### ConfigHelper JMX Integration
/// [ConfigHelper] connects to the Blade framework's settings system by looking up
/// [org.vorpal.blade.framework.v2.config.SettingsMXBean] via JNDI at
/// `java:comp/env/jmx/domainRuntime`. It compares local and remote file timestamps
/// to determine whether to read configuration locally or fetch it remotely.
///
/// ### ConfigHelper File Operations
/// Provides `saveFileLocally()` for writing JSON to disk, `loadFile()` for reading
/// with local/remote fallback logic, and `listFilesUsingFilesList()` for directory
/// listing via NIO.2 streams.
///
/// ## EJB Test Components
///
/// ### HelloWorld Remote Interface
/// The [HelloWorld] interface defines a remote EJB contract with a single
/// `getHelloWorld()` method that throws `RemoteException`.
///
/// ### HelloWorldBean Session Bean
/// [HelloWorldBean] implements [HelloWorld] as a stateless session bean. Its
/// `getHelloWorld()` method returns a greeting string for testing EJB container
/// deployment on the AdminServer.
///
/// ### HelloBean Service Bean
/// [HelloBean] provides a `sayHelloFromServiceBean()` method that logs to standard
/// output, used for basic service bean connectivity verification.
///
/// @see BladeConsoleMXBean
/// @see BladeConsole
/// @see ConfigHelper
/// @see BladeConsoleListener
package org.vorpal.blade.applications.console.config.test;
