/// Backend-only service that watches the BLADE configuration directory and
/// pushes JSON file changes into the matching `SettingsMXBean`.
///
/// Kept for backward compatibility with deployments that don't run the
/// Configurator — it performs the file-to-MBean propagation step without the
/// Configurator's editor UI, validation, or version history.
package org.vorpal.blade.applications.watcher;
