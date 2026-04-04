/// # SIP Servlet Configuration Framework V3
///
/// This package provides a flexible routing and translation configuration framework for SIP servlet applications.
/// It enables dynamic routing decisions based on SIP message attributes through pattern matching and translation maps.
///
/// ## Core Components
///
/// ### Attribute Selection and Pattern Matching
/// - [AttributeSelector] - Extracts and transforms data from SIP messages using regular expressions and replacement patterns
/// - Supports dialog type classification and attribute-based routing decisions
/// - Uses capturing groups and expression templates for flexible data extraction
///
/// ### Translation and Routing
/// - [Translation] - Represents a routing translation with attributes and route configuration
/// - [TranslationsMap] - Interface for mapping keys to translations with selector-based lookups
/// - [RouterConfig] - Main configuration class that orchestrates selectors, maps, and routing plans
///
/// ## Key Features
///
/// - **Pattern-based Attribute Extraction**: Extract routing keys from SIP headers using regular expressions
/// - **Hierarchical Translation Maps**: Support for multiple translation maps and routing plans
/// - **Generic Route Types**: Parameterized translations support any route object type
/// - **JSON Configuration**: Jackson annotations enable JSON serialization with identity references
/// - **Default Routing**: Fallback route configuration when no translation matches
///
/// ## Configuration Flow
///
/// 1. [AttributeSelector] instances extract routing keys from incoming SIP requests
/// 2. [TranslationsMap] implementations perform key-to-translation lookups
/// 3. [RouterConfig] coordinates the selection and translation process
/// 4. [Translation] objects provide the final routing configuration and attributes
///
/// @see AttributeSelector
/// @see RouterConfig
/// @see Translation
/// @see TranslationsMap
package org.vorpal.blade.framework.v3.config;
