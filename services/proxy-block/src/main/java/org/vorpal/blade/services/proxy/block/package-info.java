/// This package provides a configurable SIP proxy service that can selectively block or allow
/// SIP requests based on URI patterns and other criteria. The service is built on top of the
/// Vorpal Blade framework and provides both simple and optimized configuration mechanisms.
///
/// ## Core Components
///
/// ### Configuration Management
/// - [BlockSettingsManager] - Manages configuration settings and converts between simple and optimized formats
/// - Supports JSON-based configuration with automatic optimization for runtime performance
/// - Handles servlet lifecycle events for configuration initialization and manages the conversion from
///   simple human-readable configuration to optimized runtime configuration
///
/// ### SIP Servlet Implementation
/// - [ProxyBlockerServlet] - Main SIP servlet that processes proxy requests and responses
/// - Extends `ProxyServlet` to provide blocking functionality within the proxy chain
/// - Configured as a distributable SIP application with automatic startup (`loadOnStartup = 1`)
/// - Implements both request and response proxy handling through `proxyRequest` and `proxyResponse` methods
///
/// ### Endpoint Configuration
/// - [Endpoints] - Container for managing collections of request URI patterns
/// - Provides fluent API for building and modifying endpoint collections with methods like `addEndpoint`
/// - Supports both individual and bulk endpoint operations through constructors and setter methods
/// - Maintains internal list of request URIs for pattern matching
///
/// ## Architecture
///
/// The service uses a two-tier configuration approach:
/// 1. **Simple Configuration** - Human-readable JSON format for easy administration
/// 2. **Optimized Configuration** - Runtime-optimized format for high-performance lookups
///
/// The [BlockSettingsManager] automatically converts simple configurations to optimized
/// formats during initialization through the `initializeBlock` method, ensuring both ease of use 
/// and runtime efficiency. The manager extends `SettingsManager` and provides specialized handling
/// for block-specific configuration requirements.
///
/// ## Integration
///
/// This service integrates with the Vorpal Blade proxy framework as a web listener and SIP servlet,
/// intercepting SIP requests and responses during the proxy process to apply blocking rules based on 
/// the configured criteria. The servlet lifecycle is managed through `servletCreated` and 
/// `servletDestroyed` methods, ensuring proper initialization and cleanup of blocking configurations.
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.services.proxy.block.api]
/// Provides the REST API layer for the proxy-block service, including JAX-RS
/// endpoints for configuration management. The [LoadConfig][org.vorpal.blade.services.proxy.block.api.LoadConfig] class exposes a GET
/// endpoint for loading calling number configurations, and [CallingNumbers][org.vorpal.blade.services.proxy.block.api.CallingNumbers] stores
/// calling number to translation mappings for runtime lookups.
///
/// ### [org.vorpal.blade.services.proxy.block.optimized]
/// Provides a high-performance HashMap-based implementation for call blocking and
/// routing. [OptimizedBlockConfig][org.vorpal.blade.services.proxy.block.optimized.OptimizedBlockConfig] defines attribute selectors for extracting values
/// from SIP headers, while [OptimizedTranslation][org.vorpal.blade.services.proxy.block.optimized.OptimizedTranslation] and [OptimizedDialed][org.vorpal.blade.services.proxy.block.optimized.OptimizedDialed] provide
/// map-keyed lookup structures converted from the simple format for faster runtime
/// throughput.
///
/// ### [org.vorpal.blade.services.proxy.block.simple]
/// Provides a list-based call blocking and routing configuration that is easy to
/// read and author. [SimpleBlockConfig][org.vorpal.blade.services.proxy.block.simple.SimpleBlockConfig] stores translations as ordered lists of
/// [SimpleTranslation][org.vorpal.blade.services.proxy.block.simple.SimpleTranslation] and [SimpleDialed][org.vorpal.blade.services.proxy.block.simple.SimpleDialed] entries. These classes serve as the source
/// format for conversion to the optimized package's HashMap-based structures.
///
/// ## Configuration Framework
///
/// The proxy-block module includes a local copy of the V3 configuration framework
/// for SIP routing and translation. These packages provide the pattern-matching and
/// map-based lookup infrastructure used by the blocking service.
///
/// ### [org.vorpal.blade.framework.v3.config]
/// Flexible routing and translation configuration framework for SIP servlet
/// applications. [AttributeSelector][org.vorpal.blade.framework.v3.config.AttributeSelector] extracts routing keys from SIP headers using
/// regular expressions with capturing groups and expression templates.
/// [Translation][org.vorpal.blade.framework.v3.config.Translation] represents a routing decision with attributes and a generic route
/// object. [TranslationsMap][org.vorpal.blade.framework.v3.config.TranslationsMap] defines the interface for key-to-translation lookups
/// with selector-based evaluation. [RouterConfig][org.vorpal.blade.framework.v3.config.RouterConfig] orchestrates selectors, maps,
/// routing plans, and default routes into a complete configuration. All core classes
/// are generically parameterized to support any route type and use Jackson annotations
/// for JSON serialization with identity references and polymorphic type handling.
///
/// ### [org.vorpal.blade.framework.v3.config.maps]
/// Concrete implementations of translation maps for the configuration framework.
/// [ConfigHashMap][org.vorpal.blade.framework.v3.config.maps.ConfigHashMap] provides exact key matching by iterating through
/// `AttributeSelector` objects and performing direct `HashMap` lookups.
/// [ConfigPrefixMap][org.vorpal.blade.framework.v3.config.maps.ConfigPrefixMap] provides longest-prefix matching by progressively shortening
/// the extracted key, useful for hierarchical routing such as telephone number
/// prefix matching (area codes, country codes). Both extend
/// `HashMap<String, Translation<T>>`, implement [TranslationsMap][org.vorpal.blade.framework.v3.config.TranslationsMap], and support
/// JSON serialization with `@JsonTypeInfo` and `@JsonIdentityInfo` annotations
/// for polymorphic deserialization and object identity handling.
///
/// @see BlockSettingsManager
/// @see ProxyBlockerServlet
/// @see Endpoints
package org.vorpal.blade.services.proxy.block;
