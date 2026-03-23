/// # Queue Management Service
///
/// This package provides a comprehensive SIP-based call queue management system built on the Vorpal Blade framework.
/// It implements automatic call distribution (ACD) functionality with support for call waiting, statistics tracking,
/// and configurable queue behaviors.
///
/// ## Core Components
///
/// - [QueueServlet] - Main SIP servlet that extends B2buaServlet to handle incoming calls and route them through queues
/// - [Queue] - Central queue entity that manages call flows, maintains statistics, and handles timer-based operations
/// - [QueueCallflow] - Individual call flow handler that extends Callflow to manage the lifecycle of queued calls
/// - [Statistics] - Comprehensive statistics collector that tracks queue performance metrics over minute, hourly, and daily intervals
///
/// ## Architecture
///
/// The service operates as a SIP application that intercepts incoming calls and places them in configurable queues.
/// Each [Queue] maintains a thread-safe deque of [QueueCallflow] objects representing active calls. The system uses
/// Java timers for periodic queue processing and statistics collection.
///
/// ## Key Features
///
/// - Thread-safe concurrent call handling using `ConcurrentLinkedDeque`
/// - Configurable queue attributes through [QueueAttributes]
/// - Real-time statistics tracking with high/low watermarks
/// - Timer-based queue processing for call distribution
/// - Integration with Vorpal Blade framework's B2BUA capabilities
/// - Support for SIP servlet timers and application session management
///
/// @see org.vorpal.blade.framework.v2.b2bua.B2buaServlet
/// @see org.vorpal.blade.framework.v2.callflow.Callflow
/// @see javax.servlet.sip.SipServlet
package org.vorpal.blade.services.queue;
