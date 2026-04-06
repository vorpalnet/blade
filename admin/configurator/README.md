# Admin Configurator Module

The admin configurator module provides configuration management capabilities for the Vorpal Blade platform. This module enables dynamic configuration of services, console applications, and framework components through web-based interfaces and programmatic APIs.

## Overview

The configurator module serves as the central configuration hub for the Vorpal Blade ecosystem, offering:

- Web-based configuration interfaces for administrative tasks
- Dynamic service configuration management
- Framework-level configuration support
- Integration with console applications
- Test utilities for configuration validation

## Architecture

This module is part of the admin subsystem and works closely with the console module to provide comprehensive administrative capabilities. It bridges the gap between the framework's configuration requirements and the various services that depend on runtime configuration changes.

## Packages

### [`org.vorpal.blade.applications.console.config`](#orgvorpalbladeapplicationsconsoleconfig)

Core configuration management for console applications. Provides APIs for reading, writing, and validating configuration parameters for administrative interfaces.

### [`org.vorpal.blade.applications.console.config.test`](#orgvorpalbladeapplicationsconsoleconfigtest)

Testing utilities and mock implementations for configuration management. Contains unit test helpers and configuration validation tools for console applications.

### [`org.vorpal.blade.applications.console.webapp`](#orgvorpalbladeapplicationsconsolewebapp)

Web application components for configuration management interfaces. Implements servlets, JSP support, and web-based configuration forms for administrative users.

### [`org.vorpal.blade.framework.v2.config`](#orgvorpalbladeframeworkv2config)

Framework-level configuration management for Vorpal Blade v2. Handles core framework configuration, service bootstrapping, and runtime configuration updates.

## Dependencies

### Core Dependencies

- **org.vorpal.blade:vorpal-blade-library-framework** - Core framework library providing base configuration interfaces and utilities
- **org.apache.taglibs:taglibs-standard-impl** - JSP Standard Tag Library implementation for web interface rendering
- **xalan:xalan** - XSLT processor for XML configuration transformations and template processing

## Related Modules

### Core Framework
- [libs/framework](../libs/framework) - Base framework components and interfaces
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities and helper classes
- [libs/fsmar](../libs/fsmar) - File system management and resource handling

### Administrative Components
- [admin/console](../admin/console) - Administrative console interface and management tools

### Configurable Services
- [services/acl](../services/acl) - Access control list management service
- [services/analytics](../services/analytics) - Analytics and reporting service
- [services/hold](../services/hold) - Call hold management service
- [services/options](../services/options) - SIP OPTIONS handling service
- [services/presence](../services/presence) - Presence and availability service
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing proxy service
- [services/proxy-block](../services/proxy-block) - Request blocking proxy service
- [services/proxy-registrar](../services/proxy-registrar) - SIP registrar proxy service
- [services/proxy-router](../services/proxy-router) - SIP routing proxy service
- [services/queue](../services/queue) - Message queuing service
- [services/tpcc](../services/tpcc) - Third-party call control service
- [services/transfer](../services/transfer) - Call transfer management service

## Integration Guide

### Configuration Management

1. **Service Configuration**: Use the framework v2 config package to manage service-level configurations
2. **Web Interface**: Leverage the console webapp package for browser-based configuration management
3. **Testing**: Utilize the test package utilities for validating configuration changes

### Web Application Integration

The webapp package provides servlet-based configuration interfaces that integrate with the main admin console. JSP pages use the Apache Taglibs for dynamic content rendering and form processing.

### Framework Integration

The module integrates with the core framework through the v2 config package, enabling runtime configuration updates without service restarts. Configuration changes are propagated to dependent services through the framework's event system.

## Build Requirements

- Maven 3.x
- Java 8 or higher
- Access to Vorpal Blade framework libraries

## Usage

This module is typically deployed as part of the larger Vorpal Blade administrative suite. It should be configured alongside the admin console module and requires proper integration with the target services that need configuration management capabilities.