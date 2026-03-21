/// # NIST Core Utilities Package
///
/// This package provides fundamental core utilities and infrastructure components for parsing,
/// data structures, and common operations used throughout NIST-based applications. It serves
/// as the foundation layer offering reusable components for string processing, parsing,
/// host/network operations, and utility data structures.
///
/// ## Key Components
///
/// ### Parsing Infrastructure
/// - [LexerCore] - Core lexical analyzer for tokenizing input streams
/// - [ParserCore] - Base parser implementation providing common parsing functionality
/// - [StringTokenizer] - Enhanced string tokenization utility
/// - [Token] - Represents individual tokens in parsing operations
///
/// ### Host and Network Utilities
/// - [Host] - Represents and manipulates host information
/// - [HostPort] - Combines host and port information into a single entity
/// - [HostNameParser] - Specialized parser for host name parsing operations
///
/// ### Data Structures
/// - [NameValue] - Key-value pair implementation with enhanced functionality
/// - [DuplicateNameValueList] - Specialized list allowing duplicate name-value pairs
/// - [MultiValueMapImpl] - Map implementation supporting multiple values per key
///
/// ### Threading and Concurrency
/// - [ThreadAuditor] - Monitors and audits thread usage and lifecycle
/// - [NamingThreadFactory] - Factory for creating named threads with consistent naming patterns
///
/// ### Error Handling
/// - [InternalErrorHandler] - Centralized error handling for internal operations
///
/// ## Usage Example
///
/// ```java
/// // Creating a host-port combination
/// Host host = new Host("example.com");
/// HostPort hostPort = new HostPort(host, 8080);
/// 
/// // Using name-value pairs
/// NameValue param = new NameValue("timeout", "30000");
/// DuplicateNameValueList params = new DuplicateNameValueList();
/// params.add(param);
/// 
/// // Thread management
/// ThreadFactory factory = new NamingThreadFactory("worker");
/// ThreadAuditor auditor = new ThreadAuditor();
/// ```
///
/// This package follows NIST standards and provides thread-safe operations where applicable.
/// Most classes are designed to be lightweight and efficient for high-performance applications.
///
/// @see LexerCore
/// @see ParserCore
/// @see HostPort
/// @see NameValue
package gov.nist.core;
