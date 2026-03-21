# Framework Module

The `libs/framework` module provides the core framework infrastructure for Vorpal Blade applications, including SIP servlet extensions, session management, B2BUA functionality, and SDP processing capabilities. This module serves as the foundation for building robust SIP-based telecommunications applications.

## Overview

This framework module combines NIST SIP/SDP libraries with Vorpal Blade's proprietary framework components to deliver:

- Advanced SIP session management and call flow control
- Back-to-back User Agent (B2BUA) functionality
- Session Description Protocol (SDP) parsing and manipulation
- Proxy services and call transfer capabilities
- Analytics, logging, and monitoring infrastructure
- Configuration management and testing utilities

## Architecture

The module is organized into two main component groups:

### NIST Libraries
Standard SIP/SDP implementation providing core protocol support and parsing capabilities.

### Vorpal Blade Framework v2
Proprietary framework components built on top of NIST libraries, offering high-level abstractions and enterprise features for SIP application development.

## Packages

### `gov.nist.core`
Core NIST library utilities and base classes providing fundamental data structures and helper functions for SIP protocol implementation.

### `gov.nist.javax.sdp`
NIST implementation of the Session Description Protocol (SDP) specification, providing comprehensive SDP message handling and manipulation capabilities.

### `gov.nist.javax.sdp.fields`
SDP field implementations representing individual components of SDP messages such as origin, connection, media, and attribute fields.

### `gov.nist.javax.sdp.parser`
Parser components for converting raw SDP text into structured Java objects and vice versa, with support for validation and error handling.

### `javax.sdp`
Standard SDP API interfaces and contracts as defined by the Java Community Process, ensuring compatibility with SDP specifications.

### `org.vorpal.blade.framework.v2`
Core Vorpal Blade framework classes providing the main application infrastructure, session management, and SIP servlet extensions.

### `org.vorpal.blade.framework.v2.analytics`
Analytics and metrics collection components for monitoring application performance, call statistics, and operational insights.

### `org.vorpal.blade.framework.v2.b2bua`
Back-to-back User Agent implementation enabling advanced call routing, media manipulation, and session border controller functionality.

### `org.vorpal.blade.framework.v2.callflow`
Call flow management utilities for orchestrating complex multi-party call scenarios and business logic execution.

### `org.vorpal.blade.framework.v2.config`
Configuration management system supporting dynamic configuration updates, validation, and environment-specific settings.

### `org.vorpal.blade.framework.v2.keepalive`
Keep-alive mechanisms for maintaining SIP registrations, dialog state, and connection health monitoring.

### `org.vorpal.blade.framework.v2.logging`
Enhanced logging infrastructure with SIP-aware formatters, structured logging, and integration with monitoring systems.

### `org.vorpal.blade.framework.v2.proxy`
SIP proxy implementation with support for routing policies, load balancing, and protocol translation capabilities.

### `org.vorpal.blade.framework.v2.testing`
Testing utilities and mock objects for unit testing SIP applications, including test harnesses and scenario simulation tools.

### `org.vorpal.blade.framework.v2.transfer`
Call transfer services supporting supervised, unsupervised, and consultative transfer scenarios with proper SIP signaling.

### `org.vorpal.blade.framework.v2.transfer.api`
Public APIs for call transfer functionality, providing clean interfaces for application developers to implement transfer features.

## Dependencies

| Dependency | Purpose |
|------------|---------|
| `jackson-dataformat-xml` | XML serialization and deserialization for configuration and data exchange |
| `jackson-databind` | JSON data binding and object mapping capabilities |
| `slf4j-api` | Logging facade providing unified logging interface |
| `mbknor-jackson-jsonschema` | JSON Schema generation for configuration validation |
| `swagger-jaxrs2` | API documentation and specification generation |
| `json-path` | JSONPath query language implementation for data extraction |
| `ipaddress` | IP address parsing, validation, and manipulation utilities |
| `commons-collections4` | Enhanced collection data structures and utilities |
| `commons-email` | Email functionality for notifications and alerts |

## Related Modules

- [libs/shared](../shared) - Shared utilities and common components
- [libs/fsmar](../fsmar) - Finite State Machine and Action Router implementation  
- [admin/console](../../admin/console) - Administrative web console interface
- [admin/configurator](../../admin/configurator) - Configuration management tools
- [services](../../services) - Application services built on this framework

## Integration Guide

### Maven Dependency

Add the following dependency to your project's `pom.xml`:

```xml
<dependency>
    <groupId>org.vorpal.blade</groupId>
    <artifactId>framework</artifactId>
    <version>${vorpal.blade.version}</version>
</dependency>
```

### Basic Usage

1. **Framework Initialization**: Extend the base framework classes to create your SIP application
2. **Configuration**: Use the config package to define application-specific settings
3. **Session Management**: Leverage B2BUA components for call handling and routing
4. **Logging**: Integrate with the logging framework for structured application logs
5. **Testing**: Utilize testing utilities for comprehensive application validation

### Key Integration Points

- Implement custom call flows using the callflow package
- Configure proxy behavior through the proxy components  
- Integrate analytics for operational monitoring
- Use transfer APIs for advanced call control features

## Requirements

- Java 8 or higher
- SIP Servlet container (e.g., Oracle Communications Converged Application Server)
- Maven 3.6+ for building