# Admin Configurator Module

## Overview

The `admin/configurator` module provides configuration management capabilities for the Vorpal Blade application suite. This module serves as the central configuration hub, offering both programmatic configuration APIs and web-based configuration interfaces for system administrators.

The module encompasses configuration validation, persistence, and runtime management features that support various Vorpal Blade services and applications.

## Architecture

This module is built on the Vorpal Blade framework v2 and provides configuration services through both console applications and web applications. It integrates tightly with the admin console system to provide comprehensive configuration management capabilities.

## Packages

### org.vorpal.blade.applications.console.config
Core configuration management package containing the primary configuration APIs, data models, and business logic. This package provides:
- Configuration schema definitions
- Configuration validation and serialization
- Runtime configuration management
- Configuration persistence mechanisms

### org.vorpal.blade.applications.console.config.test  
Testing utilities and test cases for the configuration management system. Includes:
- Unit tests for configuration components
- Integration test utilities
- Mock configuration providers
- Test data fixtures

### org.vorpal.blade.applications.console.webapp
Web application components for browser-based configuration management. Features:
- JSP-based configuration interfaces
- RESTful configuration APIs
- Web form validation and processing
- Session management for configuration editing

### org.vorpal.blade.framework.v2.config
Framework-level configuration infrastructure built on Vorpal Blade framework v2. Provides:
- Base configuration abstractions
- Configuration lifecycle management
- Framework integration points
- Configuration event handling

## Dependencies

### Core Dependencies
- **org.vorpal.blade:vorpal-blade-library-framework** - Core Vorpal Blade framework providing base application infrastructure and common utilities
- **org.apache.taglibs:taglibs-standard-impl** - JSTL implementation for JSP-based web interface rendering and standard tag library support
- **xalan:xalan** - Apache Xalan XSLT processor for XML configuration transformation and processing

## Related Modules

### Framework and Core Libraries
- [libs/framework](../libs/framework) - Core framework components and base classes
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities and common tools
- [libs/fsmar](../libs/fsmar) - File system monitoring and resource management

### Administrative Modules
- [admin/console](../admin/console) - Main administrative console interface

### Service Modules
The configurator manages settings for the following service modules:

- [services/acl](../services/acl) - Access Control List service configuration
- [services/analytics](../services/analytics) - Analytics and reporting service settings
- [services/hold](../services/hold) - Call hold service configuration  
- [services/options](../services/options) - SIP OPTIONS handling service settings
- [services/presence](../services/presence) - Presence service configuration
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing proxy settings
- [services/proxy-block](../services/proxy-block) - Call blocking proxy configuration
- [services/proxy-registrar](../services/proxy-registrar) - SIP registrar proxy settings
- [services/proxy-router](../services/proxy-router) - Routing proxy configuration
- [services/queue](../services/queue) - Queue management service settings
- [services/tpcc](../services/tpcc) - Third-party call control service configuration
- [services/transfer](../services/transfer) - Call transfer service settings

## Integration Guide

### Basic Configuration Management

1. **Include the configurator module** in your Maven project dependencies
2. **Initialize configuration services** by integrating with the Vorpal Blade framework v2
3. **Access configuration APIs** through the `org.vorpal.blade.framework.v2.config` package
4. **Implement configuration listeners** to respond to configuration changes

### Web Interface Integration

1. **Deploy the web application components** to your servlet container
2. **Configure authentication and authorization** for administrative access
3. **Customize JSP templates** if needed for your specific UI requirements
4. **Integrate with the admin console** module for unified administrative experience

### Service Configuration

Each service module can be configured through this configurator by:
- Defining configuration schemas specific to the service
- Implementing configuration validation rules
- Registering configuration change listeners
- Providing service-specific configuration UI components

## Build Information

This module is built using Maven and requires Java 8 or higher. The module produces both library JAR artifacts and web application archives (WAR) for deployment.