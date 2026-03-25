/// This package provides a flexible routing and translation configuration framework for SIP servlet applications.
/// It enables dynamic routing decisions based on SIP message attributes through pattern matching and translation maps.
///
/// ## Core Components
///
/// ### Attribute Selection and Pattern Matching
/// - [AttributeSelector] - Extracts and transforms data from SIP messages using regular expressions and replacement patterns
/// - Supports dialog type classification and attribute-based routing decisions
/// - Uses capturing groups and expression templates for flexible data extraction
/// - Provides configurable attribute location specification (e.g., 'To' header) with pattern matching
///
/// ### Translation and Routing
/// - [Translation] - Represents a routing translation with attributes and route configuration of generic type T
/// - [TranslationsMap] - Interface for mapping keys to translations with selector-based lookups
/// - [RouterConfig] - Main parameterized configuration class that orchestrates selectors, maps, routing plans, and default routes
///
/// ## Key Features
///
/// - **Pattern-based Attribute Extraction**: Extract routing keys from SIP headers using regular expressions with capturing groups
/// - **Hierarchical Translation Maps**: Support for multiple translation maps and routing plans with selector chains
/// - **Generic Route Types**: Parameterized translations and router configurations support any route object type
/// - **JSON Configuration**: Jackson annotations enable JSON serialization with identity references and polymorphic type handling
/// - **Default Routing**: Fallback route configuration when no translation matches are found
/// - **Fluent API**: Method chaining support for configuration building
///
/// ## Configuration Flow
///
/// 1. [AttributeSelector] instances extract routing keys from incoming SIP requests using configured patterns
/// 2. [TranslationsMap] implementations perform key-to-translation lookups with cascading selector evaluation
/// 3. [RouterConfig] coordinates the selection and translation process across multiple maps and plans
/// 4. [Translation] objects provide the final routing configuration with associated attributes and route data
/// 5. Default routes are applied when no specific translation matches are found
///
/// ## Generic Type Support
///
/// All core classes support generic parameterization allowing the framework to work with any route type:
/// - `RouterConfig<T>` - Router configuration for route type T
/// - `Translation<T>` - Translation containing route of type T  
/// - `TranslationsMap<T>` - Map interface for translations of route type T
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.framework.v3.config.maps]
/// Provides concrete implementations of translation maps for the configuration
/// framework. Includes [ConfigHashMap][org.vorpal.blade.framework.v3.config.maps.ConfigHashMap] for exact key matching and [ConfigPrefixMap][org.vorpal.blade.framework.v3.config.maps.ConfigPrefixMap]
/// for longest-prefix matching, both implementing [TranslationsMap]. These maps
/// support JSON serialization with Jackson annotations and fluent APIs for
/// programmatic configuration of routing rules.
///
/// @see AttributeSelector
/// @see RouterConfig
/// @see Translation
/// @see TranslationsMap
package org.vorpal.blade.framework.v3.config;
