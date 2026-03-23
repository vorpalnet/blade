/// # SIP Proxy Blocking Service
///
/// This package provides a configurable SIP proxy service that can selectively block or allow
/// SIP requests based on URI patterns and other criteria. The service is built on top of the
/// Vorpal Blade framework and provides both simple and optimized configuration mechanisms.
///
/// ## Core Components
///
/// ### Configuration Management
/// - [BlockSettingsManager] - Manages configuration settings and converts between simple and optimized formats
/// - Supports JSON-based configuration with automatic optimization for runtime performance
/// - Handles servlet lifecycle events for configuration initialization and manages the conversion from
///   simple human-readable configuration to optimized runtime configuration
///
/// ### SIP Servlet Implementation
/// - [ProxyBlockerServlet] - Main SIP servlet that processes proxy requests and responses
/// - Extends `ProxyServlet` to provide blocking functionality within the proxy chain
/// - Configured as a distributable SIP application with automatic startup (`loadOnStartup = 1`)
/// - Implements both request and response proxy handling through `proxyRequest` and `proxyResponse` methods
///
/// ### Endpoint Configuration
/// - [Endpoints] - Container for managing collections of request URI patterns
/// - Provides fluent API for building and modifying endpoint collections with methods like `addEndpoint`
/// - Supports both individual and bulk endpoint operations through constructors and setter methods
/// - Maintains internal list of request URIs for pattern matching
///
/// ## Architecture
///
/// The service uses a two-tier configuration approach:
/// 1. **Simple Configuration** - Human-readable JSON format for easy administration
/// 2. **Optimized Configuration** - Runtime-optimized format for high-performance lookups
///
/// The [BlockSettingsManager] automatically converts simple configurations to optimized
/// formats during initialization through the `initializeBlock` method, ensuring both ease of use 
/// and runtime efficiency. The manager extends `SettingsManager` and provides specialized handling
/// for block-specific configuration requirements.
///
/// ## Integration
///
/// This service integrates with the Vorpal Blade proxy framework as a web listener and SIP servlet,
/// intercepting SIP requests and responses during the proxy process to apply blocking rules based on 
/// the configured criteria. The servlet lifecycle is managed through `servletCreated` and 
/// `servletDestroyed` methods, ensuring proper initialization and cleanup of blocking configurations.
///
/// @see BlockSettingsManager
/// @see ProxyBlockerServlet  
/// @see Endpoints
package org.vorpal.blade.services.proxy.block;
