/// # NIST Core Package
///
/// This package provides foundational core classes and utilities for the NIST SIP/SDP implementation.
/// It contains base classes, data structures, parsers, and utility components that are used throughout
/// the entire NIST SIP and SDP protocol stack.
///
/// ## Core Base Classes
///
/// - [GenericObject] - Abstract base class providing common functionality like encoding, debugging,
///   and reflection-based operations for all protocol objects
/// - [GenericObjectList] - Abstract base class for homogeneous linked lists of [GenericObject] instances
///
/// ## Data Structures
///
/// - [NameValue] - Generic name-value pair structure implementing [java.util.Map.Entry]
/// - [NameValueList] - Thread-safe map-based container for [NameValue] objects with encoding support
/// - [DuplicateNameValueList] - Specialized container allowing multiple values for the same key
/// - [MultiValueMap] and [MultiValueMapImpl] - Generic multi-value map interfaces and implementation
///
/// ## Network and Host Support
///
/// - [Host] - Represents hostnames and IP addresses (IPv4/IPv6) with validation and encoding
/// - [HostPort] - Combines host and port information for network endpoints
/// - [HostNameParser] - Parser for hostname and IP address strings
///
/// ## Parsing Infrastructure
///
/// - [LexerCore] - Lexical analyzer providing tokenization for all parsers in the implementation
/// - [ParserCore] - Abstract base class for all protocol parsers
/// - [StringTokenizer] - Base string tokenization utility
/// - [Token] - Represents individual lexical tokens with type and value information
///
/// ## Utility Classes
///
/// - [InternalErrorHandler] - Centralized error handling and stack trace management
/// - [ThreadAuditor] - Thread health monitoring system for detecting stuck or dead threads
/// - [NamingThreadFactory] - Thread factory for creating named thread pools
/// - [Separators] - Constants for protocol separators and delimiters
/// - [PackageNames] - Package name constants for the NIST implementation
///
/// ## Interfaces
///
/// - [Match] - Pattern matching interface for template-based matching operations
/// - [MultiMap] - Map interface returning collections instead of single objects
///
/// ## Thread Safety
///
/// Most classes in this package are not thread-safe by default, but some like [NameValueList]
/// provide thread-safe variants through constructor parameters. The [ThreadAuditor] class
/// provides monitoring capabilities for multi-threaded applications.
///
/// ## Encoding and Serialization
///
/// All core objects support string encoding for protocol serialization and are [java.io.Serializable]
/// for persistence. The encoding follows SIP/SDP protocol specifications.
///
/// @see GenericObject
/// @see NameValueList
/// @see Host
/// @see LexerCore
/// @see ParserCore
package gov.nist.core;
