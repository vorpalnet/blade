/// This package provides a Third Party Call Control (TPCC) service implementation
/// for SIP-based telecommunications applications. It enables external applications
/// to control and manipulate SIP calls through REST APIs with proper CORS support.
///
/// ## Key Components
///
/// - [TpccServlet] - Main SIP servlet that extends B2BUA functionality to handle
///   call control operations and manages asynchronous REST API responses through
///   a concurrent map of pending responses
/// - [CorsFilter] - JAX-RS filter that handles Cross-Origin Resource Sharing (CORS)
///   headers for REST API endpoints, enabling web-based clients to interact with
///   the service by implementing both request and response filtering
/// - [TpccSettings] - Configuration class that extends the framework's base
///   `Configuration` to store TPCC-specific settings
/// - [TpccSettingsSample] - Sample configuration implementation demonstrating
///   how to configure the TPCC service with logging and session parameters
///
/// ## Architecture
///
/// The service operates as a SIP servlet with REST API capabilities:
///
/// - Implements SIP application and session lifecycle management through `@WebListener`
/// - Provides asynchronous REST endpoints for call control operations
/// - Maintains a static concurrent map (`responseMap`) of pending async responses
/// - Supports distributed deployment through SIP application annotations
/// - Uses a `SettingsManager` for configuration management
///
/// The [CorsFilter] is annotated with `@Provider` and `@PreMatching` to ensure web
/// browsers can make cross-origin requests to the REST endpoints, which is essential
/// for web-based call control applications. It processes both preflight OPTIONS
/// requests and actual response headers.
///
/// ## Configuration
///
/// The service uses a configuration hierarchy where [TpccSettings] extends the
/// framework's base configuration, and [TpccSettingsSample] provides a concrete
/// implementation example with logging levels and session parameters.
///
/// ## Detailed Class Reference
///
/// ### TpccServlet
///
/// Main SIP servlet annotated with `@WebListener`, `@SipApplication(distributable=true)`,
/// and `@SipServlet(loadOnStartup=1)`. Extends `B2buaServlet` and implements both
/// `SipApplicationSessionListener` and `SipSessionListener` for comprehensive session
/// lifecycle tracking. Maintains a static `ConcurrentHashMap<String, AsyncResponse>`
/// (`responseMap`) for correlating asynchronous REST API calls with SIP responses.
/// Logs session creation, destruction, expiration, and invalidation events at INFO level.
///
/// ### CorsFilter
///
/// JAX-RS filter annotated with `@Provider` and `@PreMatching` that enables Cross-Origin
/// Resource Sharing for the REST API. Implements both `ContainerRequestFilter` and
/// `ContainerResponseFilter`. Preflight OPTIONS requests with an `Origin` header are
/// aborted with 200 OK. Response headers are enriched with `Access-Control-Allow-Methods`,
/// `Access-Control-Allow-Credentials`, `Access-Control-Allow-Headers`,
/// `Access-Control-Expose-Headers`, and `Access-Control-Allow-Origin` (reflecting the
/// request's origin).
///
/// ### TpccSettings
///
/// Configuration class extending the framework's base `Configuration`. Provides the
/// configuration structure for TPCC-specific settings such as logging and session parameters.
///
/// ### TpccSettingsSample
///
/// Default configuration with logging set to `FINE` level and session expiration set
/// to 900 seconds (15 minutes). Used as the fallback when no external configuration
/// file is present.
///
/// ## Sub-packages
///
/// ### [org.vorpal.blade.services.tpcc.callflows]
/// Contains call flow implementations for TPCC dialog creation operations. The
/// [CreateDialog][org.vorpal.blade.services.tpcc.callflows.CreateDialog] class extends `ClientCallflow` to bridge asynchronous SIP
/// INVITE/response exchanges with the JAX-RS async REST response model, enabling
/// REST callers to receive SIP outcomes as HTTP status codes.
///
/// ### [org.vorpal.blade.services.tpcc.v1]
/// Provides RESTful API endpoints for managing SIP dialogs through the [DialogAPI][org.vorpal.blade.services.tpcc.v1.DialogAPI]
/// controller. Supports dialog creation, retrieval, modification, connection, and
/// termination via standard HTTP operations. Uses asynchronous processing with
/// `ConcurrentHashMap`-based response tracking for non-blocking SIP integration.
///
/// @see TpccServlet
/// @see CorsFilter
/// @see TpccSettings
/// @see TpccSettingsSample
package org.vorpal.blade.services.tpcc;
