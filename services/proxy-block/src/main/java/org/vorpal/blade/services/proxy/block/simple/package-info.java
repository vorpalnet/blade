/// # Simple Block Configuration Package
///
/// This package provides a simple call blocking and routing configuration system for SIP proxy services.
/// It supports pattern-based matching of SIP headers and provides flexible routing and blocking capabilities
/// based on calling and dialed numbers.
///
/// ## Key Components
///
/// - [SimpleBlockConfig] - Main configuration class that defines selectors for From, To, and Request-URI headers
/// - [SimpleBlockConfigSample] - Sample configuration with predefined regex patterns for common SIP header parsing
/// - [SimpleTranslation] - Defines routing rules that map calling numbers to dialed number configurations
/// - [SimpleDialed] - Represents dialed number configurations with forwarding destinations
///
/// ## Configuration Structure
///
/// The configuration system uses a hierarchical approach:
/// 1. **Header Selectors** - Extract and normalize values from SIP From, To, and Request-URI headers using `AttributeSelector` objects
/// 2. **Translation Rules** - Map calling numbers to specific dialed number configurations through a list of [SimpleTranslation] objects
/// 3. **Dialed Number Rules** - Define forwarding destinations for specific dialed numbers via [SimpleDialed] configurations
/// 4. **Default Routing** - Fallback routing when no specific rules match using the `defaultRoute` [SimpleTranslation]
///
/// ## Pattern Matching
///
/// The package leverages [org.vorpal.blade.framework.v2.config.AttributeSelector] for flexible
/// pattern-based extraction and transformation of SIP header values using regular expressions
/// and template-based substitutions. The [SimpleBlockConfigSample] class provides predefined
/// regex patterns for extracting user information from standard SIP URI formats.
///
/// ## Architecture
///
/// [SimpleBlockConfig] extends `Configuration` and serves as the root configuration object containing:
/// - Header selectors (`fromSelector`, `toSelector`, `ruriSelector`) for SIP message processing
/// - A collection of calling number translations (`callingNumbers`)
/// - A default routing configuration (`defaultRoute`)
///
/// Each [SimpleTranslation] can contain either a list of [SimpleDialed] objects for specific
/// dialed number handling or direct forwarding destinations via the `forwardTo` list.
///
/// @see SimpleBlockConfig
/// @see SimpleBlockConfigSample
/// @see SimpleTranslation
/// @see SimpleDialed
package org.vorpal.blade.services.proxy.block.simple;
