/// # Queue Configuration Package
///
/// This package provides configuration management for the Vorpal Blade queue service system.
/// It defines the structure and attributes needed to configure queue behavior including
/// timing parameters, announcements, and queue routing settings.
///
/// ## Key Classes
///
/// - [QueueConfig] - Main configuration class that extends `RouterConfig` and manages queue definitions through a map of queue identifiers to their attributes
/// - [QueueAttributes] - Serializable configuration object defining individual queue parameters such as polling period, processing rate, ring duration, ring period, and announcement settings
/// - [QueueSettingsManager] - Specialized settings manager that handles configuration initialization and management for the queue service, including creation of actual FIFO queue instances
/// - [QueueConfigSample] - Sample configuration implementation providing default queue setup examples for testing and demonstration purposes
///
/// ## Configuration Structure
///
/// The configuration system uses a hierarchical approach where:
/// - [QueueConfig] serves as the root configuration containing a map of queue definitions accessed by string identifiers
/// - Each queue is identified by a string key and associated with [QueueAttributes] that define its operational behavior
/// - [QueueAttributes] encapsulates timing parameters (`period` for polling intervals, `rate` for transactions per cycle, `ringDuration` for response timeouts, `ringPeriod` for ring intervals) and announcement file settings
/// - [QueueSettingsManager] provides the infrastructure for loading configurations and initializing the underlying queue data structures
///
/// ## Configuration Parameters
///
/// Queue behavior is controlled through several key parameters:
/// - **Period**: Milliseconds between queue polling cycles
/// - **Rate**: Number of transactions processed per polling cycle  
/// - **Ring Duration**: Timeout in milliseconds for announcement or agent response before requeueing
/// - **Ring Period**: Interval between ring attempts
/// - **Announcement**: Audio file or message to play to queued callers
///
/// @see [org.vorpal.blade.framework.v2.config.RouterConfig]
/// @see [org.vorpal.blade.framework.v2.config.SettingsManager]
package org.vorpal.blade.services.queue.config;
