/// # Logging Framework for SIP Applications
///
/// This package provides a comprehensive logging framework specifically designed for SIP servlet applications,
/// offering enhanced logging capabilities with session awareness, ANSI color support, and sequence diagram generation.
///
/// ## Core Components
///
/// - [Logger] - Extended logger with SIP session-aware logging, stack trace handling, JSON object serialization, and visual call flow diagrams
/// - [LogManager] - Centralized logger management with automatic configuration, lifecycle handling, and servlet context integration
/// - [LogParameters] - Configuration parameters for logging behavior including file rotation, levels, and color settings
/// - [LogParametersDefault] - Default logging configuration values providing sensible defaults for production deployments
/// - [LogFormatter] - Custom log formatter providing compact, readable output with timestamp formatting in `LEVEL YYYY-MM-DD HH:mm:ss.SSS - message` format
/// - [Color] - ANSI color code utility for enhanced console output with various text styling options including bold, underlined, and bright variants
///
/// ## Key Features
///
/// ### Session-Aware Logging
/// The framework integrates deeply with SIP servlet contexts through specialized logging methods that
/// automatically include session identifiers ([SipSession], [SipApplicationSession]) and message context for improved traceability.
/// Supports logging with [SipServletMessage], [ServletTimer], and [Proxy] contexts.
///
/// ### Configurable Output
/// Supports both console and file-based logging with configurable rotation, sizing, and formatting options through [LogParameters].
/// Color output can be enabled or disabled based on deployment environment requirements using the `colorsEnabled` property.
///
/// ### Sequence Diagram Generation
/// Provides visual call flow logging capabilities through the `superArrow` methods for debugging complex SIP interactions 
/// and message flows with directional indicators and participant identification.
///
/// ### Multiple Logging Levels
/// Supports separate logging levels for different components including sequence diagrams (`sequenceDiagramLoggingLevel`), 
/// configuration (`configurationLoggingLevel`), and analytics (`analyticsLoggingLevel`), allowing fine-grained control over log verbosity.
/// Uses [LogParameters.LoggingLevel] enum for consistent level management.
///
/// ### Advanced Capabilities
/// - Stack trace logging with SIP context through `logStackTrace` methods
/// - JSON object serialization for complex data structures via `logObjectAsJson` methods
/// - Analytics event logging with [Event] and [Attribute] support
/// - Variable substitution in configuration paths using servlet context properties
///
/// ## Configuration
///
/// The logging system is configured through [LogParameters] which supports JSON-based configuration
/// with variable substitution for deployment-specific paths and settings. Default configurations
/// are provided through [LogParametersDefault] with standard values for log directory, file size,
/// rotation count, and logging levels.
///
/// File size parsing supports units through [LogParameters.Unit] enum, and the system integrates
/// with WebLogic's [weblogic.kernel.KernelLogManager] for enterprise deployment compatibility.
///
/// @see Logger
/// @see LogManager  
/// @see LogParameters
/// @see LogParametersDefault
/// @see LogFormatter
/// @see Color
package org.vorpal.blade.framework.v2.logging;
