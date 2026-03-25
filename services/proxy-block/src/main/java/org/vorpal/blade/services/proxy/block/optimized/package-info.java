/// This package provides an optimized implementation for SIP call blocking and routing
/// services. It uses `HashMap`-based lookup tables for high-performance call flow control,
/// converting the list-based simple configuration into map-keyed structures.
///
/// ## Core Components
///
/// - [OptimizedBlockConfig] - Main configuration class extending `Configuration`
/// - [OptimizedTranslation] - Per-calling-number translation rules with dialed number mappings
/// - [OptimizedDialed] - Per-dialed-number forwarding destinations
/// - [OptimizedBlockConfigSample] - Sample configuration with predefined regex patterns
///
/// ## Configuration Class
///
/// ### OptimizedBlockConfig
/// [OptimizedBlockConfig] extends `Configuration` and defines three `AttributeSelector`
/// fields for extracting normalized values from SIP headers:
/// - `fromSelector` - extracts the calling party number from the From header
/// - `toSelector` - extracts the dialed number from the To header
/// - `ruriSelector` - optionally extracts values from the Request-URI
///
/// It stores calling number translations in a `Map<String, OptimizedTranslation>`
/// keyed by calling number string, and provides a `defaultRoute` fallback.
///
/// ### forwardTo() Routing Logic
/// The static `forwardTo(config, request)` method implements the core routing algorithm:
/// 1. Extracts From, To, and Request-URI keys using the configured selectors
/// 2. Looks up the calling number in `callingNumbers`, falling back to `defaultRoute`
/// 3. If a match has `dialedNumbers`, looks up the To key for per-destination routing
/// 4. Falls back to the translation's `forwardTo` list if no dialed match
/// 5. Shuffles the forward-to list for load distribution and picks the first entry
/// 6. Resolves `${variable}` placeholders from merged selector attributes
/// 7. Validates the final SIP URI via `SipFactory.createURI()`
///
/// ## Translation Classes
///
/// ### OptimizedTranslation
/// [OptimizedTranslation] holds a `Map<String, OptimizedDialed>` for dialed number
/// routing and a `List<String>` of `forwardTo` SIP URIs as defaults. It provides:
/// - `forwardTo(sipUri)` - fluent method to add a forwarding destination
/// - `addDialedNumber(dialedNumber)` - creates and inserts an [OptimizedDialed] entry
/// - A constructor accepting [SimpleTranslation][org.vorpal.blade.services.proxy.block.simple.SimpleTranslation] for conversion from simple format
///
/// ### OptimizedDialed
/// [OptimizedDialed] contains a `List<String>` of `forwardTo` SIP URIs for a specific
/// dialed number. It provides:
/// - `forwardTo(sipUri)` - fluent method to add a destination, returns `this` for chaining
/// - A constructor accepting [SimpleDialed][org.vorpal.blade.services.proxy.block.simple.SimpleDialed] for conversion from simple format
///
/// ## Sample Configuration
///
/// ### OptimizedBlockConfigSample
/// [OptimizedBlockConfigSample] extends [OptimizedBlockConfig] and pre-populates:
/// - From selector with regex extracting `fromUser` and defaulting `fromPort` to 5060
/// - To selector with regex extracting `toUser`
/// - A default route forwarding to `${toProto}:${toUser}@${toHost}:${fromPort}`
/// - Sample calling number entries for `"8165551234"` and `"alice"` with per-dialed
///   number overrides demonstrating the routing hierarchy
///
/// @see org.vorpal.blade.framework.v2.config.Configuration
/// @see org.vorpal.blade.framework.v2.config.AttributeSelector
/// @see org.vorpal.blade.services.proxy.block.simple
package org.vorpal.blade.services.proxy.block.optimized;
