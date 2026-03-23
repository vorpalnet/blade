/// # Optimized Block Configuration Package
///
/// This package provides an optimized implementation for SIP call blocking and routing services.
/// It builds upon the Vorpal Blade framework to offer high-performance call flow control through
/// pre-compiled translation tables and attribute-based request routing.
///
/// ## Core Components
///
/// - [OptimizedBlockConfig] - Main configuration class that defines attribute selectors for FROM, TO, and Request-URI headers, along with calling number mappings and default routing rules. Provides the static `forwardTo()` method for determining routing destinations.
/// - [OptimizedTranslation] - Represents translation rules for specific calling numbers, containing dialed number mappings and forward-to destinations. Can be constructed from simple translation configurations.
/// - [OptimizedDialed] - Handles dialed number routing with forward-to SIP URI lists for call destinations. Supports fluent configuration through method chaining.
/// - [OptimizedBlockConfigSample] - Sample implementation demonstrating typical configuration patterns with predefined regex patterns for header normalization of FROM and TO selectors.
///
/// ## Key Features
///
/// The package supports:
/// - Attribute-based request routing using configurable selectors for FROM, TO, and Request-URI headers
/// - Calling number to translation rule mappings for personalized routing via the `callingNumbers` map
/// - Dialed number specific forwarding rules through nested routing tables
/// - Default routing fallback mechanisms when no specific rules match
/// - Integration with `SipServletRequest` processing for real-time call routing decisions
/// - Conversion from simple block configurations to optimized formats for better performance
/// - Fluent API design with method chaining for configuration building
///
/// ## Configuration Structure
///
/// The optimized configuration uses a hierarchical structure:
/// 1. Attribute selectors extract normalized values from SIP headers
/// 2. Calling numbers map to specific translation rules
/// 3. Translation rules contain dialed number mappings and default forward-to lists
/// 4. Dialed number entries specify final routing destinations
/// 5. Default routes handle unmatched scenarios
///
/// This optimized implementation is designed for high-throughput SIP proxy scenarios where
/// performance-critical call routing decisions must be made efficiently with minimal processing overhead.
///
/// @see [org.vorpal.blade.framework.v2.config.Configuration]
/// @see [org.vorpal.blade.framework.v2.config.AttributeSelector]
/// @see [org.vorpal.blade.services.proxy.block.simple]
package org.vorpal.blade.services.proxy.block.optimized;
