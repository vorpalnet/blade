/// # Queue Configuration Package
///
/// This package provides configuration management for the Vorpal Blade queue service system.
/// It defines the structure and attributes needed to configure queue behavior including
/// timing parameters, announcements, and queue routing settings.
///
/// ## Key Classes
///
/// - [QueueConfig] - Main configuration class that extends RouterConfig and manages queue definitions
/// - [QueueAttributes] - Serializable configuration object defining individual queue parameters such as period, rate, ring duration, and announcements
/// - [QueueSettingsManager] - Settings manager that handles configuration initialization and management for the queue service
/// - [QueueConfigSample] - Sample configuration implementation providing default queue setup examples
///
/// ## Configuration Structure
///
/// The configuration system uses a hierarchical approach where:
/// - [QueueConfig] serves as the root configuration containing a map of queue definitions
/// - Each queue is identified by a string key and associated with [QueueAttributes]
/// - [QueueAttributes] encapsulates timing parameters (`period`, `rate`, `ringDuration`, `ringPeriod`) and announcement settings
/// - [QueueSettingsManager] provides the infrastructure for loading and managing these configurations
///
/// @see org.vorpal.blade.framework.v2.config.RouterConfig
/// @see org.vorpal.blade.framework.v2.config.SettingsManager
package org.vorpal.blade.services.queue.config;
