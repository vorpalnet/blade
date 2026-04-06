/// This package contains the JMX management interface used by the Blade configurator
/// application to interact with remote service configurations at runtime.
///
/// ## Key Classes
///
/// - [SettingsMXBean] - JMX MXBean interface for remote configuration file management
///
/// ## JMX Management Interface
///
/// ### SettingsMXBean
/// The [SettingsMXBean] interface is annotated with `@MXBean` and defines operations
/// for reading, writing, and reloading configuration files across domain, cluster,
/// and server scopes. It is registered under the ObjectName pattern
/// `vorpal.blade:Name=<app>,Type=Configuration`.
///
/// ### Configuration Scoping
/// The `configType` parameter accepted by most methods selects the configuration
/// scope: domain-level, cluster-level, or server-level. The `getLastModified(configType)`
/// method returns the timestamp of the last modification for a given scope, enabling
/// the configurator console to detect stale local copies.
///
/// ### File I/O Operations
/// The interface provides streaming file access:
/// - `openForWrite(configType)` - opens a configuration file for writing
/// - `openForRead(configType)` - opens a configuration file for reading
/// - `read()` - reads the next line from the opened file, returns `null` at EOF
/// - `write(line)` - writes a line to the opened file
/// - `close()` - closes the currently open file handle
///
/// ### Runtime Reload
/// The `reload()` method triggers the remote service to re-read its configuration
/// from disk, applying changes without requiring a full application restart.
///
/// @see SettingsMXBean
package org.vorpal.blade.framework.v2.config;
