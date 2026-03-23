/// # TPCC Service Package
///
/// This package provides a Third Party Call Control (TPCC) service implementation
/// for SIP-based telecommunications applications. It enables external applications
/// to control and manipulate SIP calls through REST APIs with proper CORS support.
///
/// ## Key Components
///
/// - [TpccServlet] - Main SIP servlet that extends B2BUA functionality to handle
///   call control operations and manages asynchronous REST API responses
/// - [CorsFilter] - JAX-RS filter that handles Cross-Origin Resource Sharing (CORS)
///   headers for REST API endpoints, enabling web-based clients to interact with
///   the service
/// - [TpccSettings] - Configuration class that extends the framework's base
///   Configuration to store TPCC-specific settings
/// - [TpccSettingsSample] - Sample configuration implementation demonstrating
///   how to configure the TPCC service with logging and session parameters
///
/// ## Architecture
///
/// The service operates as a SIP servlet with REST API capabilities:
///
/// - Implements SIP application and session lifecycle management
/// - Provides asynchronous REST endpoints for call control
/// - Maintains a concurrent map of pending async responses
/// - Supports distributed deployment through SIP application annotations
///
/// The CORS filter ensures web browsers can make cross-origin requests to the
/// REST endpoints, which is essential for web-based call control applications.
///
/// @see TpccServlet
/// @see CorsFilter
/// @see TpccSettings
package org.vorpal.blade.services.tpcc;
