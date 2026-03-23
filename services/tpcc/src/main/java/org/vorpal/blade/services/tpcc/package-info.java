/// # TPCC Service Package
///
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
/// @see TpccServlet
/// @see CorsFilter
/// @see TpccSettings
/// @see TpccSettingsSample
package org.vorpal.blade.services.tpcc;
