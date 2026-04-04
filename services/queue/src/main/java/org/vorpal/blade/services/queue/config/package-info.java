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
/// ## Detailed Class Reference
///
/// ### QueueConfig
///
/// Root configuration class extending `RouterConfig`. Contains a `Map<String, QueueAttributes>`
/// named `queues` that maps queue identifiers (e.g., "fast", "medium", "slow") to their
/// corresponding attribute definitions. Provides fluent builder methods `setQueues` and
/// `addQueue` for programmatic configuration. Inherits routing capabilities including
/// selectors, translation maps, and translation plans from the framework.
///
/// ### QueueAttributes
///
/// Serializable POJO defining the operational parameters for a single queue instance.
/// Uses a fluent builder pattern where each setter returns `this`. Fields include:
///
/// - `period` (Integer) -- milliseconds between queue polling cycles
/// - `rate` (Integer) -- number of transactions processed per polling cycle
/// - `ringDuration` (Integer) -- milliseconds to wait for agent response before requeueing
/// - `ringPeriod` (Integer) -- interval between ring attempts
/// - `announcement` (String) -- SIP URI of the announcement server to play to queued callers
///
/// Includes a copy constructor for cloning attribute sets.
///
/// ### QueueSettingsManager
///
/// Specialized `SettingsManager<QueueConfig>` that overrides the `initialize` method to
/// create actual FIFO queue instances. During initialization, it iterates over the configured
/// queue names, creates new `Queue` objects via `QueueServlet.queues` if they do not already
/// exist, and calls `queue.initialize(qa)` to apply the attributes. Handles re-initialization
/// gracefully by preserving existing queue instances and only updating their attributes.
///
/// ### QueueConfigSample
///
/// Sample configuration demonstrating three queue tiers with different throughput profiles:
///
/// - **fast** -- 5-second polling, 10 transactions/cycle, 60-second ring duration
/// - **medium** -- 15-second polling, 5 transactions/cycle, 60-second ring duration
/// - **slow** -- 30-second polling, 1 transaction/cycle, 60-second ring duration
///
/// Also demonstrates selector and translation map setup using `ConfigHashMap` and
/// `ConfigPrefixMap` to route calls to specific queues based on the To header's user part.
///
/// @see [org.vorpal.blade.framework.v2.config.RouterConfig]
/// @see [org.vorpal.blade.framework.v2.config.SettingsManager]
package org.vorpal.blade.services.queue.config;
