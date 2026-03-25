/// This package provides a comprehensive SIP-based call queue management system built on the Vorpal Blade framework.
/// It implements automatic call distribution (ACD) functionality with support for call waiting, statistics tracking,
/// and configurable queue behaviors.
///
/// ## Core Components
///
/// - [QueueServlet] - Main SIP servlet that extends `B2buaServlet` to handle incoming calls and route them through queues
/// - [Queue] - Central queue entity that manages call flows, maintains statistics, and handles timer-based operations
/// - [QueueCallflow] - Individual call flow handler that extends `Callflow` to manage the lifecycle of queued calls
/// - [Statistics] - Comprehensive statistics collector that tracks queue performance metrics over minute, hourly, and daily intervals
///
/// ## Architecture
///
/// The service operates as a SIP application that intercepts incoming calls and places them in configurable queues.
/// Each [Queue] maintains a thread-safe `ConcurrentLinkedDeque` of [QueueCallflow] objects representing active calls. 
/// The system uses Java timers for periodic queue processing and statistics collection.
///
/// ### Queue Processing
///
/// The [Queue] class manages individual queues with configurable attributes and maintains real-time statistics.
/// Each queue uses timer-based processing to handle call distribution and tracking. Queue operations are thread-safe
/// to support concurrent call handling.
///
/// ### Call Flow Management
///
/// [QueueCallflow] extends the Vorpal Blade `Callflow` class to handle SIP call states specific to queue operations.
/// It manages the complete lifecycle of queued calls including processing inbound requests, handling timers,
/// and state transitions through the `QueueState` enumeration.
///
/// ### Statistics Tracking
///
/// The [Statistics] class provides comprehensive monitoring with separate high/low watermark tracking for
/// minute, hourly, and daily intervals. Statistics are collected using timer-based tasks that run at
/// randomized intervals to distribute system load.
///
/// ## Key Features
///
/// - Thread-safe concurrent call handling using `ConcurrentLinkedDeque`
/// - Configurable queue attributes through `QueueAttributes`
/// - Real-time statistics tracking with high/low watermarks across multiple time intervals
/// - Timer-based queue processing for automated call distribution
/// - Integration with Vorpal Blade framework's B2BUA capabilities
/// - Support for SIP servlet timers and application session management
/// - State-based call flow management with configurable queue behaviors
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.services.queue.config]
/// Provides configuration management for the queue service, defining queue behavior
/// parameters such as polling period, processing rate, ring duration, and announcement
/// settings. [QueueConfig][org.vorpal.blade.services.queue.config.QueueConfig] serves as the root configuration containing a map of queue
/// definitions, while [QueueAttributes][org.vorpal.blade.services.queue.config.QueueAttributes] encapsulates the operational parameters for
/// individual queue instances. [QueueSettingsManager][org.vorpal.blade.services.queue.config.QueueSettingsManager] handles initialization and
/// creation of actual FIFO queue objects.
///
/// @see [org.vorpal.blade.framework.v2.b2bua.B2buaServlet]
/// @see [org.vorpal.blade.framework.v2.callflow.Callflow]
/// @see [javax.servlet.sip.SipServlet]
package org.vorpal.blade.services.queue;
