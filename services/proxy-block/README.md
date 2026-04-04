# Proxy Block Service

A comprehensive SIP proxy blocking service module that provides configurable request filtering and blocking capabilities within the Vorpal Blade SIP servlet framework.

## Overview

The `services/proxy-block` module implements a sophisticated SIP request blocking system that can filter and block SIP messages based on various criteria such as caller ID, destination patterns, user agents, and custom rules. The service supports multiple blocking strategies including simple rule-based blocking and optimized high-performance filtering.

This module is part of the broader Vorpal Blade SIP proxy ecosystem and integrates seamlessly with other proxy services to provide comprehensive call control and security features.

## Features

- **Flexible Blocking Rules**: Configure blocking based on headers, URIs, methods, and custom patterns
- **Multiple Implementation Strategies**: Choose between simple rule-based or optimized high-performance blocking
- **Real-time Configuration**: Dynamic rule updates without service restart
- **Integration Ready**: Works with ACL, analytics, and other proxy services
- **Performance Optimized**: Efficient filtering algorithms for high-throughput environments

## Architecture

The module follows a layered architecture:

- **API Layer**: Defines interfaces and contracts for blocking services
- **Implementation Layer**: Provides concrete blocking strategies (simple and optimized)
- **Configuration Layer**: Handles rule configuration and management
- **Integration Layer**: Connects with other Vorpal Blade services

## Packages

### [`org.vorpal.blade.framework.v3.config`](#orgvorpalbladeframeworkv3config)
Core configuration framework classes for managing service settings, rule definitions, and runtime configuration updates. Provides the foundation for all configurable components in the blocking service.

### [`org.vorpal.blade.framework.v3.config.maps`](#orgvorpalbladeframeworkv3configmaps)
Specialized configuration mapping utilities for handling complex rule sets and pattern matching configurations. Includes support for various data structures used in blocking rule definitions.

### [`org.vorpal.blade.services.proxy.block`](#orgvorpalbladeservicesproxyblock)
Main service package containing the core blocking service implementation, rule engine, and integration points with the SIP servlet container. Houses the primary service orchestration logic.

### [`org.vorpal.blade.services.proxy.block.api`](#orgvorpalbladeservicesproxyblockapi)
Public API definitions including interfaces, data transfer objects, and service contracts. Defines the programming interface for integrating with the blocking service from other modules.

### [`org.vorpal.blade.services.proxy.block.optimized`](#orgvorpalbladeservicesproxyblockoptimized)
High-performance blocking implementation optimized for high-throughput environments. Features advanced algorithms for pattern matching, caching, and efficient rule evaluation.

### [`org.vorpal.blade.services.proxy.block.simple`](#orgvorpalbladeservicesproxyblocksimple)
Straightforward blocking implementation suitable for basic use cases and smaller deployments. Provides easy-to-understand rule processing with minimal overhead.

## Dependencies

### Core Dependencies
- **org.vorpal.blade:vorpal-blade-library-framework**: Core framework providing SIP servlet abstractions, configuration management, and service lifecycle support

## Related Modules

### Core Framework
- [libs/framework](../libs/framework) - Base framework libraries and utilities
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities and common components
- [libs/fsmar](../libs/fsmar) - Finite State Machine framework for call processing

### Administration
- [admin/console](../admin/console) - Management console for monitoring and administration
- [admin/configurator](../admin/configurator) - Configuration management interface

### Complementary Services
- [services/acl](../services/acl) - Access Control Lists for additional security layers
- [services/analytics](../services/analytics) - Call analytics and reporting integration
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing for proxy services
- [services/proxy-registrar](../services/proxy-registrar) - SIP registration handling
- [services/proxy-router](../services/proxy-router) - Call routing and forwarding logic

### Supporting Services
- [services/hold](../services/hold) - Call hold functionality
- [services/options](../services/options) - SIP OPTIONS handling
- [services/presence](../services/presence) - Presence and availability services
- [services/queue](../services/queue) - Call queuing and management
- [services/tpcc](../services/tpcc) - Third-party call control
- [services/transfer](../services/transfer) - Call transfer capabilities

## Integration Guide

### Basic Setup

1. Add the module dependency to your Maven project
2. Configure blocking rules through the configuration framework
3. Deploy alongside other proxy services for complete functionality

### Configuration

The service uses the Vorpal Blade configuration framework for rule management. Rules can be defined statically in configuration files or updated dynamically through the admin interfaces.

### Service Integration

The blocking service integrates with other proxy services through:
- **ACL Service**: For additional authorization checks
- **Analytics Service**: For blocked call reporting and statistics
- **Router Service**: For alternative routing of blocked calls

### Performance Considerations

- Use the optimized implementation for high-volume environments
- Configure appropriate caching strategies for frequently accessed rules
- Monitor blocking performance through the analytics integration

## Building

```bash
mvn clean compile
mvn test
mvn package
```

## License

Part of the Vorpal Blade SIP Servlet Framework. See project root for license information.