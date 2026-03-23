/// # NIST Core Package
///
/// This package provides foundational core classes and utilities for the NIST SIP/SDP implementation.
/// It contains base classes, data structures, parsers, and utility components that are used throughout
/// the entire NIST SIP and SDP protocol stack.
///
/// ## Core Base Classes
///
/// - [GenericObject] - Abstract base class providing common functionality like encoding, debugging,
///   pattern matching, and reflection-based operations for all protocol objects
/// - [GenericObjectList] - Abstract base class for homogeneous linked lists of [GenericObject] instances
///   with encoding and merge capabilities
///
/// ## Data Structures
///
/// - [NameValue] - Generic name-value pair structure implementing `java.util.Map.Entry` with encoding support
/// - [NameValueList] - Thread-safe map-based container for [NameValue] objects with semicolon-separated encoding
/// - [DuplicateNameValueList] - Specialized container allowing multiple values for the same key
/// - [MultiValueMap] and [MultiValueMapImpl] - Generic multi-value map interfaces and implementation
/// - [MultiMap] - Map interface returning collections instead of single objects
///
/// ## Network and Host Support
///
/// - [Host] - Represents hostnames and IP addresses (IPv4/IPv6) with validation, encoding, and DNS resolution
/// - [HostPort] - Combines host and port information for network endpoints
/// - [HostNameParser] - Parser for hostname and IP address strings with IPv6 reference support
///
/// ## Parsing Infrastructure
///
/// - [LexerCore] - Lexical analyzer providing tokenization with keyword tables and token recognition
/// - [ParserCore] - Abstract base class for all protocol parsers with debug support
/// - [StringTokenizer] - Base string tokenization utility with character-by-character parsing
/// - [Token] - Represents individual lexical tokens with type and value information
///
/// ## Utility Classes
///
/// - [InternalErrorHandler] - Centralized error handling and stack trace management
/// - [ThreadAuditor] - Thread health monitoring system for detecting stuck or dead threads with ping mechanism
/// - [NamingThreadFactory] - Thread factory for creating named thread pools
/// - [Separators] - Constants interface for protocol separators and delimiters
/// - [PackageNames] - Constants interface for package name references in the NIST implementation
///
/// ## Interfaces
///
/// - [Match] - Pattern matching interface for template-based matching operations
/// - [MultiMap] - Map interface extending `java.util.Map` with collection-based value removal
/// - [MultiValueMap] - Generic multi-value map interface extending `java.util.Map`
/// - [Separators] - Interface defining protocol separator constants
/// - [PackageNames] - Interface defining package name constants
///
/// ## Thread Safety
///
/// Most classes in this package are not thread-safe by default. [NameValueList] provides optional
/// thread safety through constructor parameters using `ConcurrentHashMap`. The [ThreadAuditor] class
/// provides monitoring capabilities for multi-threaded applications through periodic ping mechanisms.
///
/// ## Encoding and Serialization
///
/// All core objects support string encoding for protocol serialization following SIP/SDP specifications.
/// Most classes implement `java.io.Serializable` for persistence. Encoding typically uses semicolon
/// separation for lists and name=value format for parameters.
///
/// ## Pattern Matching and Templating
///
/// The package provides sophisticated pattern matching through the [Match] interface and template-based
/// matching in [GenericObject]. This allows partial matching and template-based operations on protocol
/// objects without relying solely on regular expressions.
///
/// @see GenericObject
/// @see NameValueList
/// @see Host
/// @see LexerCore
/// @see ParserCore
/// @see ThreadAuditor
package gov.nist.core;
