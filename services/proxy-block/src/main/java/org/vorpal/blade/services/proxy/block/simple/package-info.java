/// This package provides a list-based call blocking and routing configuration system
/// for SIP proxy services. It uses ordered lists for calling number translations,
/// making it straightforward to configure but less performant than the optimized
/// package for large rule sets.
///
/// ## Key Components
///
/// - [SimpleBlockConfig] - Main configuration class extending `Configuration`
/// - [SimpleBlockConfigSample] - Sample configuration with predefined regex patterns
/// - [SimpleTranslation] - Routing rules mapping calling numbers to destinations
/// - [SimpleDialed] - Per-dialed-number forwarding destinations
///
/// ## Configuration Class
///
/// ### SimpleBlockConfig
/// [SimpleBlockConfig] extends `Configuration` and defines three `AttributeSelector`
/// fields for extracting normalized values from SIP headers:
/// - `fromSelector` - extracts the calling party identifier from the From header
/// - `toSelector` - extracts the dialed number from the To header
/// - `ruriSelector` - extracts values from the Request-URI
///
/// Calling number translations are stored as a `List<SimpleTranslation>` in the
/// `callingNumbers` field, and a `defaultRoute` [SimpleTranslation] provides fallback
/// routing when no match is found.
///
/// ## Translation Classes
///
/// ### SimpleTranslation
/// [SimpleTranslation] represents a routing rule for a specific calling number. Each
/// translation has:
/// - `id` - the calling number string to match against
/// - `dialedNumbers` - an optional `List<SimpleDialed>` for per-dialed-number routing
/// - `forwardTo` - a `List<String>` of default SIP URIs used when no dialed number matches
///
/// ### SimpleDialed
/// [SimpleDialed] represents forwarding rules for a specific dialed number with:
/// - `id` - the dialed number string to match against
/// - `forwardTo` - a `List<String>` of SIP URI destinations for call forwarding
///
/// ## Sample Configuration
///
/// ### SimpleBlockConfigSample
/// [SimpleBlockConfigSample] extends [SimpleBlockConfig] and pre-populates a complete
/// working configuration including:
/// - **From selector** - regex pattern extracting `fromUser` (stripping `+1` prefix)
///   with `fromPort` defaulting to `5060` via `additionalExpressions`
/// - **To selector** - regex pattern extracting `toUser` from the To header
/// - **Request-URI selector** - regex pattern extracting `ruriUser`, `ruriHost`,
///   `ruriPort`, and `ruriUriparams`
/// - **Default route** - forwards to `${ruriProto}:${ruriUser}@${ruriHost}:${fromPort}`
/// - **Sample entries** - calling numbers `8165551234`, `9135551234`, and `alice` with
///   per-dialed-number overrides demonstrating the configuration hierarchy
///
/// ### Variable Substitution
/// Forwarding URIs support `${variable}` placeholders resolved from selector-extracted
/// named capture groups, e.g., `${toProto}:${toUser}@${toHost}:${fromPort}`.
///
/// ## Conversion to Optimized Format
///
/// The simple classes serve as source data for conversion to the optimized package.
/// [OptimizedTranslation][org.vorpal.blade.services.proxy.block.optimized.OptimizedTranslation] and [OptimizedDialed][org.vorpal.blade.services.proxy.block.optimized.OptimizedDialed] accept [SimpleTranslation] and
/// [SimpleDialed] in their constructors, converting the list-based structure to
/// `HashMap`-keyed lookups for higher throughput.
///
/// @see SimpleBlockConfig
/// @see SimpleBlockConfigSample
/// @see SimpleTranslation
/// @see SimpleDialed
/// @see org.vorpal.blade.services.proxy.block.optimized
package org.vorpal.blade.services.proxy.block.simple;
