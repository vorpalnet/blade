/// This package provides comprehensive configuration management capabilities for SIP servlet applications
/// built on the Vorpal Blade framework. It includes translation maps, routing configurations, attribute
/// selectors, and JSON serialization/deserialization support for SIP-specific types.
///
/// ## Core Configuration Classes
///
/// - [Configuration] - Base configuration class with logging, session parameters, and utility methods for parsing human-readable durations and numbers
/// - [RouterConfig] - Router configuration with selectors, translation maps and default routes using a simple routing approach
/// - [RouterConfig2] - Alternative router configuration with enhanced routing plan support for more complex routing scenarios
/// - [SettingsManager] - Manages configuration files through automatic JSON loading, merging domain/cluster/server configs, and JMX integration
/// - [Settings] - JMX-enabled settings implementation for runtime configuration management with file I/O operations
///
/// ## Translation and Mapping
///
/// The package provides multiple translation map implementations optimized for different lookup patterns:
///
/// - [TranslationsMap] - Abstract base class for different translation map implementations with selector support
/// - [ConfigHashMap] - Translation map using HashMap for exact key-based lookups
/// - [ConfigLinkedHashMap] - Translation map using LinkedHashMap to preserve insertion order during iteration
/// - [ConfigTreeMap] - Translation map using TreeMap for sorted key-based lookups
/// - [ConfigPrefixMap] - Translation map with prefix-based lookup using iterative substring matching
/// - [ConfigPrefixMapApache] - Translation map using Apache PatriciaTrie for efficient prefix-based lookups
/// - [ConfigAddressMap] - Translation map using IP address trie for efficient CIDR-based lookups supporting both IPv4 and IPv6
/// - [ConfigIPv4Map] - Deprecated IPv4-specific translation map (use [ConfigAddressMap] instead)
/// - [AddressMap] - Map implementation using IP address trie for efficient address-based lookups
/// - [Translation] - Represents a translation rule with attributes, request URI, and nested translation maps
///
/// ## Selectors and Conditions
///
/// - [Selector] - Defines selectors for extracting keys from SIP messages using regex patterns and replacement expressions
/// - [AttributeSelector] - Selector for extracting session attributes from SIP messages with dialog association support and JSONPath expressions
/// - [Condition] - Contains collections of comparisons associated with message attributes for complex request matching
/// - [Comparison] - Comparison operation mapping operator names to expressions for request matching with support for various SIP header operations
/// - [ComparisonList] - List of comparisons that are ANDed together when checking request conditions
/// - [RequestCondition] - Interface for checking conditions against SIP servlet requests
///
/// ## Data Containers
///
/// - [Attribute] - Simple name-value pair representing a single attribute with getter/setter methods
/// - [Attributes] - Container for a value and its associated list of attributes
/// - [AttributesKey] - Container for a lookup key and its associated attributes map
/// - [NameValuePair] - Generic parameterized name-value pair container
/// - [RegExRoute] - Container for regex-matched routes with key, header, matcher, and extracted attributes
/// - [ServerPair] - Represents a primary/secondary server pair for failover routing scenarios
/// - [ServerPool] - Pool of server pairs with load balancing and connection attempt configuration including random selection
///
/// ## Session Management
///
/// - [SessionParameters] - Configuration parameters for SIP session management including expiration and session selectors
/// - [SessionParametersDefault] - Default session parameters with standard values and From header selector
/// - [KeepAliveParameters] - Configuration parameters for SIP session keep-alive behavior with session timer support
/// - [KeepAliveParametersDefault] - Default keep-alive parameters with standard session timer values
///
/// ## JSON Serialization Support
///
/// The package includes comprehensive Jackson serializers and deserializers for SIP-specific types:
///
/// ### SIP Object Serialization
/// - [JsonAddressSerializer]/[JsonAddressDeserializer] - SIP Address object conversion
/// - [JsonSipUriSerializer]/[JsonSipUriDeserializer] - SipURI object conversion
/// - [JsonUriSerializer]/[JsonUriDeserializer] - SIP URI object conversion
///
/// ### IP Address Serialization
/// - [JsonIPAddressSerializer]/[JsonIPAddressDeserializer] - IPAddress object conversion
/// - [JsonIPv4AddressSerializer]/[JsonIPv4AddressDeserializer] - IPv4Address object conversion
/// - [JsonIPv6AddressSerializer]/[JsonIPv6AddressDeserializer] - IPv6Address object conversion
/// - [InetAddressKeyDeserializer] - Jackson key deserializer for IPAddress map keys
///
/// ### Generic Serialization
/// - [JsonObjectSerializer] - Generic Object serialization using toString() method
///
/// ## JMX Management
///
/// - [SettingsMXBean] - JMX MXBean interface for managing configuration settings at runtime with file operations and reload capability
///
/// @see [org.vorpal.blade.framework.v2.callflow.Callflow]
/// @see [org.vorpal.blade.framework.v2.logging.Logger]
/// @see [org.vorpal.blade.framework.v2.analytics.Analytics]
package org.vorpal.blade.framework.v2.config;
