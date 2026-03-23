# Framework Module

A comprehensive Java framework module that provides core SIP/SDP functionality and the Vorpal Blade framework for building telecommunications applications. This module serves as the foundation for SIP-based services and applications.

## Overview

The `libs/framework` module combines NIST SIP/SDP libraries with the Vorpal Blade framework v2 to provide:

- **SIP/SDP Protocol Support**: Complete implementation of SIP and SDP protocols based on NIST libraries
- **Application Framework**: High-level abstractions for building SIP applications
- **B2BUA Functionality**: Back-to-Back User Agent capabilities for call routing and manipulation
- **Analytics and Logging**: Comprehensive monitoring and debugging tools
- **Configuration Management**: Flexible configuration system with JSON Schema validation
- **Testing Framework**: Tools for unit and integration testing of SIP applications

## Package Structure

### SIP/SDP Core Packages

#### `gov.nist.core`
Core utilities and base classes for NIST SIP implementation. Provides fundamental data structures, parsing utilities, and common functionality used across the SIP stack.

#### `gov.nist.javax.sdp`
Main SDP (Session Description Protocol) implementation classes. Handles creation, parsing, and manipulation of SDP messages for multimedia session negotiation.

#### `gov.nist.javax.sdp.fields`
SDP field implementations for all standard SDP attributes including media descriptions, connection information, timing, and attributes.

#### `gov.nist.javax.sdp.parser`
SDP parsing engine that converts text-based SDP messages into structured Java objects and vice versa.

#### `javax.sdp`
Standard JSR-141 SDP API interfaces and exceptions. Provides the public API contract for SDP operations.

### Vorpal Blade Framework Packages

#### `org.vorpal.blade.framework.v2`
Core framework classes including base servlets, session management, and fundamental SIP application building blocks.

#### `org.vorpal.blade.framework.v2.analytics`
Analytics and metrics collection framework for monitoring application performance, call statistics, and system health.

#### `org.vorpal.blade.framework.v2.b2bua`
Back-to-Back User Agent implementation for creating proxy applications that can modify, route, and control SIP calls.

#### `org.vorpal.blade.framework.v2.callflow`
Call flow management and state machine implementations for handling complex multi-party call scenarios.

#### `org.vorpal.blade.framework.v2.config`
Configuration management system with support for JSON Schema validation, hot-reloading, and environment-specific settings.

#### `org.vorpal.blade.framework.v2.keepalive`
Keep-alive mechanisms for maintaining SIP registrations and monitoring connection health.

#### `org.vorpal.blade.framework.v2.logging`
Enhanced logging framework with SIP-aware formatters, structured logging, and debugging utilities.

#### `org.vorpal.blade.framework.v2.proxy`
High-level proxy implementations for common SIP proxy patterns including stateful and stateless proxying.

#### `org.vorpal.blade.framework.v2.testing`
Testing utilities and mock objects for unit testing SIP applications and integration testing.

#### `org.vorpal.blade.framework.v2.transfer`
Call transfer implementations supporting both attended and unattended transfer scenarios.

#### `org.vorpal.blade.framework.v2.transfer.api`
API definitions and interfaces for call transfer functionality.

## Dependencies

- **jackson-dataformat-xml**: XML serialization/deserialization for configuration and data exchange
- **jackson-databind**: JSON data binding for configuration and API operations
- **slf4j-api**: Logging abstraction layer for consistent logging across the framework
- **mbknor-jackson-jsonschema**: JSON Schema generation and validation for configuration management
- **swagger-jaxrs2**: API documentation and validation for REST endpoints
- **json-path**: JSONPath expressions for configuration queries and data extraction
- **ipaddress**: IP address parsing and manipulation utilities
- **commons-collections4**: Enhanced collection utilities and data structures
- **commons-email**: Email functionality for notifications and alerts

## Integration

### Basic Usage

1. **Extend Framework Classes**: Create SIP servlets by extending `org.vorpal.blade.framework.v2` base classes
2. **Configure Applications**: Use the configuration framework to define application-specific settings
3. **Implement Call Logic**: Utilize B2BUA or proxy classes for call handling logic
4. **Add Analytics**: Integrate analytics components for monitoring and metrics

### Configuration

The framework uses JSON-based configuration with schema validation. Configuration files should be structured according to the JSON schemas defined in the config package.

### Logging

Configure SLF4J-compatible loggers to take advantage of the framework's enhanced logging capabilities, including SIP message tracing and structured log output.

## Related Modules

### Shared Libraries
- [libs/shared/bin](../shared/bin) - Shared binary utilities
- [libs/fsmar](../fsmar) - Finite State Machine and Rules engine

### Administrative Tools
- [admin/console](../../admin/console) - Web-based administration console
- [admin/configurator](../../admin/configurator) - Configuration management tools

### Services
- [services/acl](../../services/acl) - Access Control Lists
- [services/analytics](../../services/analytics) - Analytics collection service
- [services/hold](../../services/hold) - Call hold functionality
- [services/options](../../services/options) - SIP OPTIONS handling
- [services/presence](../../services/presence) - SIP presence server
- [services/proxy-balancer](../../services/proxy-balancer) - Load balancing proxy
- [services/proxy-block](../../services/proxy-block) - Call blocking proxy
- [services/proxy-registrar](../../services/proxy-registrar) - SIP registrar service
- [services/proxy-router](../../services/proxy-router) - Call routing proxy
- [services/queue](../../services/queue) - Call queuing service
- [services/tpcc](../../services/tpcc) - Third-party call control
- [services/transfer](../../services/transfer) - Call transfer service

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>framework</artifactId>
<version>${project.version}</version>
```