# Proxy Block Service

A Maven module that provides call blocking and access control functionality for the Vorpal Blade SIP framework. This service implements various strategies for blocking unwanted calls based on configurable rules and patterns.

## Overview

The `proxy-block` service is part of the Vorpal Blade services ecosystem, offering comprehensive call blocking capabilities for SIP proxy servers. It provides both simple rule-based blocking and optimized pattern matching algorithms to efficiently filter incoming requests based on various criteria such as caller ID, destination numbers, IP addresses, and custom business rules.

## Features

- Multiple blocking strategies (simple and optimized implementations)
- Configurable blocking rules and patterns
- Integration with the Vorpal Blade configuration framework
- High-performance pattern matching for large rule sets
- RESTful API for runtime configuration
- Extensible architecture for custom blocking logic

## Package Structure

### [`org.vorpal.blade.framework.v3.config`](#orgvorpalbladeframeworkv3config)
Core configuration framework classes providing the foundation for service configuration management, including configuration loading, validation, and runtime updates.

### [`org.vorpal.blade.framework.v3.config.maps`](#orgvorpalbladeframeworkv3configmaps)
Configuration mapping utilities that handle the transformation between configuration data structures and runtime objects, supporting complex configuration hierarchies.

### [`org.vorpal.blade.services.proxy.block`](#orgvorpalbladeservicesproxyblock)
Main service implementation containing the core blocking logic, service lifecycle management, and integration points with the SIP proxy infrastructure.

### [`org.vorpal.blade.services.proxy.block.api`](#orgvorpalbladeservicesproxyblockapi)
REST API interfaces and controllers for managing blocking rules, providing endpoints for rule creation, modification, deletion, and status monitoring.

### [`org.vorpal.blade.services.proxy.block.optimized`](#orgvorpalbladeservicesproxyblockoptimized)
High-performance blocking implementation using optimized data structures and algorithms for handling large-scale rule sets with minimal latency impact.

### [`org.vorpal.blade.services.proxy.block.simple`](#orgvorpalbladeservicesproxyblocksimple)
Straightforward blocking implementation suitable for smaller deployments with moderate rule complexity, prioritizing simplicity and maintainability.

## Dependencies

- **org.vorpal.blade:vorpal-blade-library-framework** - Core framework library providing SIP processing capabilities, configuration management, and service infrastructure

## Related Modules

### Core Infrastructure
- [libs/framework](../libs/framework) - Core framework implementation
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities and common components
- [libs/fsmar](../libs/fsmar) - Finite State Machine Augmented Registry library

### Administration
- [admin/console](../admin/console) - Administrative console interface
- [admin/configurator](../admin/configurator) - Configuration management tools

### Complementary Services
- [services/acl](../services/acl) - Access Control List service
- [services/analytics](../services/analytics) - Call analytics and reporting
- [services/hold](../services/hold) - Call hold functionality
- [services/options](../services/options) - SIP OPTIONS handling
- [services/presence](../services/presence) - Presence and availability service
- [services/queue](../services/queue) - Call queuing service
- [services/transfer](../services/transfer) - Call transfer capabilities
- [services/tpcc](../services/tpcc) - Third-party call control

### Proxy Services
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing for SIP traffic
- [services/proxy-registrar](../services/proxy-registrar) - SIP registration handling
- [services/proxy-router](../services/proxy-router) - SIP routing logic

## Integration

The proxy-block service integrates seamlessly with other Vorpal Blade components:

1. **Configuration**: Utilizes the framework's configuration system for rule management
2. **Proxy Chain**: Plugs into the SIP proxy processing chain alongside routing and balancing services
3. **Analytics**: Provides blocking statistics to the analytics service
4. **ACL Integration**: Coordinates with the ACL service for comprehensive access control
5. **Administrative Interface**: Exposes management capabilities through the admin console

## Getting Started

1. Ensure the framework dependency is available in your Maven repository
2. Add this module as a dependency to your Vorpal Blade service deployment
3. Configure blocking rules through the configuration framework or REST API
4. The service will automatically integrate with the SIP proxy processing pipeline

For detailed configuration and usage examples, refer to the individual package documentation and the related admin modules.