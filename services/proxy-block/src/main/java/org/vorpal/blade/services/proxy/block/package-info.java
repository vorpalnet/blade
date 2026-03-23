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
/// - Handles servlet lifecycle events for configuration initialization
///
/// ### SIP Servlet Implementation
/// - [ProxyBlockerServlet] - Main SIP servlet that processes proxy requests and responses
/// - Extends ProxyServlet to provide blocking functionality within the proxy chain
/// - Configured as a distributable SIP application with automatic startup
///
/// ### Endpoint Configuration
/// - [Endpoints] - Container for managing collections of request URI patterns
/// - Provides fluent API for building and modifying endpoint collections
/// - Supports both individual and bulk endpoint operations
///
/// ## Architecture
///
/// The service uses a two-tier configuration approach:
/// 1. **Simple Configuration** - Human-readable JSON format for easy administration
/// 2. **Optimized Configuration** - Runtime-optimized format for high-performance lookups
///
/// The [BlockSettingsManager] automatically converts simple configurations to optimized
/// formats during initialization, ensuring both ease of use and runtime efficiency.
///
/// ## Integration
///
/// This service integrates with the Vorpal Blade proxy framework, intercepting SIP requests
/// and responses during the proxy process to apply blocking rules based on the configured
/// criteria.
///
/// @see BlockSettingsManager
/// @see ProxyBlockerServlet
/// @see Endpoints
package org.vorpal.blade.services.proxy.block;
