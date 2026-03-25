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
/// ### NameValueList Thread Safety
///
/// [NameValueList] accepts a `boolean sync` constructor parameter. When `true`, the internal map
/// uses `ConcurrentHashMap` instead of `LinkedHashMap`. The map is lazily initialized on first
/// write to conserve memory. All key lookups are case-insensitive (converted to lowercase).
///
/// ### LexerCore Concurrency
///
/// [LexerCore] uses static `ConcurrentHashMap` instances for its global symbol table and lexer
/// tables, allowing multiple parser threads to share keyword definitions without blocking. Each
/// lexer instance maintains its own buffer pointer state, so instances are not shared across threads.
///
/// ### ThreadAuditor Monitoring
///
/// [ThreadAuditor] uses a `ConcurrentHashMap` to track monitored threads. Threads register via
/// `addCurrentThread()` and receive a [ThreadAuditor.ThreadHandle] they use to periodically ping.
/// The auditor's `auditThreads()` method scans all handles and reports threads that failed to ping
/// since the last audit cycle. The ping interval is configurable via `setPingIntervalInMillisecs()`.
///
/// ## Encoding and Serialization
///
/// All core objects support string encoding for protocol serialization following SIP/SDP specifications.
/// Most classes implement `java.io.Serializable` for persistence. Encoding typically uses semicolon
/// separation for lists and name=value format for parameters.
///
/// ### GenericObject Encoding
///
/// [GenericObject] defines the abstract `encode()` method that all subclasses must implement.
/// It also provides `encode(StringBuilder)` for efficient buffer-based serialization and
/// `debugDump()` for reflection-based pretty printing of all non-private fields with indentation.
///
/// ### NameValue Encoding
///
/// [NameValue] encodes as `name=value` by default, with configurable separators via `setSeparator()`.
/// Values can be double-quoted via `setQuotedValue()`, and flag parameters (name-only, no value)
/// are supported through the `isFlagParameter` constructor option. The class implements
/// `java.util.Map.Entry` for compatibility with standard Java map operations.
///
/// ### GenericObjectList Encoding
///
/// [GenericObjectList] extends `LinkedList` and encodes its elements in semicolon-separated form.
/// It enforces type homogeneity through a `myClass` field and supports deep cloning, list
/// concatenation, and object-by-object merging via `mergeObjects()`.
///
/// ## Pattern Matching and Templating
///
/// The package provides sophisticated pattern matching through the [Match] interface and template-based
/// matching in [GenericObject]. This allows partial matching and template-based operations on protocol
/// objects without relying solely on regular expressions.
///
/// ### Template-Based Matching
///
/// [GenericObject] uses reflection-based `match()` to compare objects field by field. A `null` field
/// in the template matches anything (wildcard). String fields use case-insensitive comparison, and
/// empty strings are treated as wildcards. Nested [GenericObject] and [GenericObjectList] fields
/// are matched recursively. This approach handles the non-canonical ordering of SIP headers and
/// parameters without requiring regular expressions.
///
/// ### Match Interface
///
/// The [Match] interface defines a single `match(String)` method that can be implemented using
/// any regular expression library (e.g., Jakarta regexp). This is set on a [GenericObject] via
/// `setMatcher()` and used for custom pattern matching beyond the built-in reflection-based approach.
///
/// ### Object Merging
///
/// [GenericObject] provides `merge()` for recursively overriding fields with values from another
/// object of the same class. Null fields in the merge source are skipped, allowing selective
/// field updates. This is useful for generating SIP message templates and overriding specific
/// fields from incoming messages.
///
/// ## Parsing Infrastructure Details
///
/// ### StringTokenizer
///
/// [StringTokenizer] is the lowest-level parser, operating on a `char[]` buffer with a pointer (`ptr`)
/// for character-by-character traversal. It provides static utility methods `isAlpha()`, `isDigit()`,
/// `isAlphaDigit()`, and `isHexDigit()` used throughout the parsing hierarchy. The `lookAhead()`
/// method peeks at upcoming characters without consuming them.
///
/// ### LexerCore Token Types
///
/// [LexerCore] extends [StringTokenizer] with token classification. It defines token type constants
/// for all SIP separator characters (SEMICOLON, COLON, etc.) and character classes (ALPHA, DIGIT, IPV6).
/// Keyword tokens are registered via `addKeyword()` and looked up case-insensitively. The `match()`
/// method consumes and validates expected tokens, throwing `ParseException` on mismatch.
///
/// ### ParserCore
///
/// [ParserCore] is the abstract base for all protocol-specific parsers. It holds a [LexerCore] reference
/// and provides the `nameValue()` method for parsing `name=value` and `name="value"` pairs, which is
/// fundamental to SIP header parameter parsing. Debug entry/exit tracking is available via `dbg_enter()`
/// and `dbg_leave()`.
///
/// ### HostNameParser
///
/// [HostNameParser] extends [ParserCore] and handles DNS hostnames, IPv4 addresses, and IPv6 references
/// (including bracket notation). It supports IPv6 address scope zone stripping via the system property
/// `gov.nist.core.STRIP_ADDR_SCOPES`. The `hostPort()` method parses `host:port` combinations with
/// optional whitespace tolerance for Via headers.
///
/// ## Network Host Representation
///
/// ### Host Address Types
///
/// [Host] distinguishes three address types: HOSTNAME (DNS names, stored lowercase), IPV4ADDRESS,
/// and IPV6ADDRESS (auto-detected by presence of colons). IPv6 addresses are encoded with square
/// brackets when not already in reference form. DNS resolution is performed lazily via
/// `getInetAddress()` with caching to avoid repeated lookups.
///
/// ### HostPort Composition
///
/// [HostPort] combines a [Host] with an optional port number (defaulting to -1 when unset).
/// It encodes as `host:port` when a port is present. The `merge()` method preserves unset
/// ports from the merge source, supporting SIP URI default port behavior.
///
/// ## Multi-Value Data Structures
///
/// ### DuplicateNameValueList
///
/// [DuplicateNameValueList] wraps [MultiValueMapImpl] to allow multiple [NameValue] entries
/// with the same key. This is required for SIP headers that can appear multiple times with
/// identical parameter names. All key lookups are case-insensitive.
///
/// ### MultiValueMapImpl
///
/// [MultiValueMapImpl] implements [MultiValueMap] using a lazily initialized `HashMap` of
/// `ArrayList` values. The `put(key, value)` method appends to the existing list rather than
/// replacing it. The `values()` method flattens all lists into a single collection.
/// Memory is conserved by deferring map creation until the first write operation.
///
/// @see GenericObject
/// @see NameValueList
/// @see Host
/// @see LexerCore
/// @see ParserCore
/// @see ThreadAuditor
package gov.nist.core;
