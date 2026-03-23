/// # Proxy Block API Package
///
/// This package provides the API layer for the proxy-block service, which manages
/// call blocking and number translation configurations. It contains REST endpoints
/// for configuration management and data structures for handling calling number
/// mappings with optimized translations.
///
/// ## Key Classes
///
/// - [LoadConfig] - JAX-RS REST service providing configuration loading endpoints with OpenAPI documentation
/// - [CallingNumbers] - `Map` implementation for managing calling number to translation mappings
///
/// ## Configuration Management
///
/// The [LoadConfig] class serves as an example implementation demonstrating how the
/// proxy-block application can load configurations at runtime through RESTful endpoints.
/// It is designed as a reference implementation - the actual proxy-block service will
/// implement a client to retrieve configurations from external sources. The service
/// includes comprehensive OpenAPI annotations for API documentation and operates under
/// the "v1" path namespace.
///
/// ## Number Translation Storage
///
/// The [CallingNumbers] class implements the [java.util.Map] interface to provide
/// efficient storage and retrieval of calling number translations. It maps string-based
/// calling numbers to `OptimizedTranslation` objects and includes a specialized
/// `addCallingNumber` method for convenient number registration beyond the standard
/// Map operations.
///
/// @see LoadConfig
/// @see CallingNumbers
package org.vorpal.blade.services.proxy.block.api;
