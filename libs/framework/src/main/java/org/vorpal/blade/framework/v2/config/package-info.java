/// # Configuration Management Framework
///
/// This package provides a comprehensive configuration management system using the [SettingsManager] generics class
/// to dynamically read and write configuration files. The framework supports JSON serialization/deserialization,
/// SIP message routing, and flexible translation mapping capabilities.
///
/// ## Core Configuration Classes
///
/// - [SettingsManager] - Main entry point for configuration management with generic type support
/// - [Configuration] - Base configuration class with logging and session parameters
/// - [Settings] - Core settings container with automatic configuration loading capabilities
/// - [SessionParameters] - SIP session management configuration and defaults
/// - [KeepAliveParameters] - Network keep-alive configuration settings
///
/// ## Routing and Translation
///
/// - [RouterConfig] - Router configuration with selectors and translation maps
/// - [RouterConfig2] - Enhanced router configuration with additional features
/// - [Translation] - Translation rule definitions with attributes and request URI mapping
/// - [TranslationsMap] - Abstract base class for translation lookup maps
/// - [Selector] - Extracts keys from SIP messages using configurable regex patterns
/// - [AttributeSelector] - Advanced attribute selection with extensive configuration options
///
/// ## Condition Matching
///
/// - [Condition] - Collection of comparisons for sophisticated request matching
/// - [Comparison] - Individual comparison operations with multiple matching criteria
/// - [ComparisonList] - Manages collections of comparison operations
/// - [Attribute] - Defines extractable attributes from SIP messages
/// - [Attributes] - Container for attribute collections and keys
///
/// ## Map Implementations
///
/// The package provides various specialized map implementations for different routing scenarios:
///
/// - [ConfigHashMap] - Standard hash-based configuration mapping
/// - [ConfigLinkedHashMap] - Ordered configuration mapping preserving insertion order
/// - [ConfigTreeMap] - Sorted configuration mapping for range-based lookups
/// - [ConfigAddressMap] - Network address-based configuration mapping
/// - [ConfigIPv4Map] - IPv4-specific address mapping with CIDR support
/// - [ConfigPrefixMap] - Prefix-based string matching for routing decisions
/// - [ConfigPrefixMapApache] - Apache Commons-based prefix mapping implementation
/// - [AddressMap] - General-purpose address mapping utilities
///
/// ## JSON Serialization Support
///
/// Custom Jackson serializers and deserializers for SIP and network types:
///
/// - [JsonSipUriSerializer] / [JsonSipUriDeserializer] - SIP URI handling
/// - [JsonAddressSerializer] / [JsonAddressDeserializer] - Network address serialization
/// - [JsonIPAddressSerializer] / [JsonIPAddressDeserializer] - IP address handling
/// - [JsonIPv4AddressSerializer] / [JsonIPv4AddressDeserializer] - IPv4-specific serialization
/// - [JsonIPv6AddressSerializer] / [JsonIPv6AddressDeserializer] - IPv6-specific serialization
/// - [JsonUriSerializer] / [JsonUriDeserializer] - General URI serialization
/// - [JsonObjectSerializer] - Generic object serialization utilities
///
/// ## Utility Classes
///
/// - [ServerPair] - Represents server endpoint pairs for load balancing
/// - [ServerPool] - Manages collections of server endpoints with health monitoring
/// - [NameValuePair] - Generic name-value pair container for configuration parameters
/// - [RegExRoute] - Regular expression-based routing rules
///
/// ## Usage Example
///
/// ```java
/// // Load configuration using SettingsManager
/// SettingsManager<RouterConfig> manager = new SettingsManager<>(RouterConfig.class);
/// RouterConfig config = manager.getCurrent();
/// 
/// // Configure routing with selectors and translations
/// Selector selector = new Selector();
/// selector.setPattern("sip:(.+)@(.+)");
/// selector.setReplacement("/**
 * Use the SettingsManager Generics class to dynamically read and write configuration files.
 *
 * <h2>Key Classes:</h2>
 * <ul>
 *   <li>{@link org.vorpal.blade.framework.v2.config.SettingsManager} - Main entry point for configuration management</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.Configuration} - Base configuration class with logging and session parameters</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.RouterConfig} - Router configuration with selectors and translation maps</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.Translation} - Translation rule with attributes and request URI</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.TranslationsMap} - Abstract base for translation lookup maps</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.Selector} - Extracts keys from SIP messages using regex patterns</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.Condition} - Collection of comparisons for request matching</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.SessionParameters} - SIP session management configuration</li>
 * </ul>
 *
 * @since 2.0
 */
");
/// 
/// Translation translation = new Translation();
/// translation.setRequestUri("sip:destination@example.com");
/// config.getTranslations().put("key", translation);
/// 
/// // Save updated configuration
/// manager.save();
/// ```
///
/// @since 2.0
/// @see SettingsManager
/// @see RouterConfig
/// @see Translation
package org.vorpal.blade.framework.v2.config;