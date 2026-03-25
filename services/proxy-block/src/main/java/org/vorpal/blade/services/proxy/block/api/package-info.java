/// This package provides the REST API layer for the proxy-block service, which manages
/// call blocking and number translation configurations. It contains JAX-RS endpoints
/// for configuration management and data structures for calling number mappings.
///
/// ## Key Classes
///
/// - [LoadConfig] - JAX-RS REST service providing configuration loading endpoints
/// - [CallingNumbers] - `Map` implementation for calling number to translation mappings
///
/// ## REST Endpoints
///
/// ### LoadConfig Service
/// The [LoadConfig] class is a JAX-RS resource annotated with `@OpenAPIDefinition`
/// providing the "Proxy-Block" API (version 1). It serves as a reference
/// implementation demonstrating runtime configuration loading. The actual proxy-block
/// service will implement a client to retrieve configurations from external sources.
///
/// ### GET /v1/config/load/{id}
/// The single endpoint accepts a path parameter `id` and returns a [CallingNumbers]
/// map as JSON. It builds a sample configuration demonstrating:
/// - Calling number registration via `addCallingNumber()`
/// - Default forwarding with `forwardTo(sipUri)`
/// - Per-dialed-number forwarding via `addDialedNumber().forwardTo()`
///
/// OpenAPI response codes: 200 (OK), 404 (Not Found), 500 (Internal Server Error).
///
/// ## Number Translation Storage
///
/// ### CallingNumbers Map
/// The [CallingNumbers] class implements `Map<String, OptimizedTranslation>` to store
/// calling number to translation mappings. It provides `addCallingNumber(callingNumber)`
/// as a convenience factory method that creates an [OptimizedTranslation][org.vorpal.blade.services.proxy.block.optimized.OptimizedTranslation], inserts it
/// into the map keyed by the calling number, and returns it for fluent configuration.
/// The standard `Map` methods are currently stub implementations.
///
/// ### Data Flow
/// A typical configuration flow: create a [CallingNumbers] map, call
/// `addCallingNumber("18165551234")` to get an [OptimizedTranslation][org.vorpal.blade.services.proxy.block.optimized.OptimizedTranslation], set its default
/// `forwardTo` URI, then call `addDialedNumber("19135556789")` on the translation to
/// define per-dialed-number routing via [OptimizedDialed][org.vorpal.blade.services.proxy.block.optimized.OptimizedDialed].
///
/// @see LoadConfig
/// @see CallingNumbers
/// @see org.vorpal.blade.services.proxy.block.optimized.OptimizedTranslation
/// @see org.vorpal.blade.services.proxy.block.optimized.OptimizedDialed
package org.vorpal.blade.services.proxy.block.api;
