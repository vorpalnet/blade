/// # Custom Logging Framework
///
/// This package provides a specialized logging framework designed for SIP applications with advanced 
/// features including session-aware logging and automatic sequence diagram generation for call flows.
///
/// ## Key Features
///
/// - **Session-aware logging**: Track and correlate log messages across SIP sessions
/// - **Sequence diagram generation**: Automatically generate call flow diagrams from log data
/// - **Flexible configuration**: Comprehensive logging parameters and formatting options
/// - **Console enhancement**: ANSI color support for improved readability
/// - **File-based logging**: Configurable file output with rotation and management
///
/// ## Core Classes
///
/// - [Logger] - Extended logger implementation with session tracking and call flow diagram capabilities
/// - [LogManager] - Central manager for creating and managing application-specific logger instances
/// - [LogParameters] - Configuration container for file-based logging settings and behavior
/// - [LogFormatter] - Custom formatting utilities for structured log output
/// - [LogParametersDefault] - Default configuration values and settings
///
/// ## Console Utilities
///
/// - `ConsoleColors` - ANSI color code constants and utilities
/// - `Color` - Color management and formatting helpers
///
/// ## Usage Example
///
/// ```java
/// // Initialize logging with custom parameters
/// LogParameters params = new LogParameters()
///     .setLogLevel(Level.INFO)
///     .setEnableSequenceDiagrams(true);
///
/// LogManager manager = new LogManager(params);
/// Logger logger = manager.getLogger("sip.application");
///
/// // Session-aware logging
/// logger.info("Processing SIP INVITE", sessionId);
/// logger.debug("Call flow step", callId, sequenceNumber);
/// ```
///
/// This framework is particularly useful for SIP servlets, B2BUA applications, and other 
/// telecommunications software where understanding call flows and session relationships 
/// is critical for debugging and monitoring.
///
/// @since 2.0
/// @see Logger
/// @see LogManager
/// @see LogParameters
package org.vorpal.blade.framework.v2.logging;