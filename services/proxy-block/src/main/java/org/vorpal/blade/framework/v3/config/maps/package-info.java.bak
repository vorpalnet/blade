/// # Configuration Maps Package
///
/// This package provides concrete implementations of translation maps for the Vorpal Blade framework's
/// configuration system. Translation maps are responsible for routing and translating SIP servlet requests
/// based on configurable attribute selectors and lookup strategies.
///
/// ## Key Classes
///
/// - [ConfigHashMap] - HashMap-based translation map that performs exact key matching for request routing
/// - [ConfigPrefixMap] - HashMap-based translation map that performs prefix-based matching for request routing
///
/// ## Features
///
/// Both implementation classes provide:
/// - Attribute selector-based request routing using [AttributeSelector] lists
/// - JSON serialization support with Jackson annotations for configuration persistence
/// - Generic type support for flexible translation target types
/// - Builder-pattern methods for fluent configuration
/// - Integration with SIP servlet request processing
///
/// The maps extend `HashMap<String, Translation<T>>` and implement the [TranslationsMap] interface,
/// providing different lookup strategies:
/// - **ConfigHashMap**: Exact string matching with optional default route fallback
/// - **ConfigPrefixMap**: Prefix-based matching for hierarchical routing scenarios
///
/// @see org.vorpal.blade.framework.v3.config.TranslationsMap
/// @see org.vorpal.blade.framework.v3.config.Translation
/// @see org.vorpal.blade.framework.v3.config.AttributeSelector
package org.vorpal.blade.framework.v3.config.maps;
