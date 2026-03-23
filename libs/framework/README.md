# Framework Module

## Overview

The `libs/framework` module provides the core framework infrastructure for the Vorpal Blade SIP application server. This module combines NIST SIP/SDP protocol implementations with a comprehensive application framework for building SIP-based services, including B2BUA functionality, call flow management, analytics, and service orchestration.

## Architecture

This module serves as the foundation layer for all Vorpal Blade services, providing:

- **Protocol Support**: Complete SIP and SDP protocol implementation via NIST libraries
- **Application Framework**: Core framework classes for building SIP applications
- **Service Components**: Reusable components for common SIP service patterns
- **Infrastructure Services**: Logging, configuration, analytics, and testing utilities

## Package Structure

### NIST Protocol Libraries

#### `gov.nist.core`
Core NIST library components providing fundamental SIP protocol infrastructure and utilities.

#### `gov.nist.javax.sdp`
NIST implementation of the Java SDP (Session Description Protocol) API, providing comprehensive SDP message handling and manipulation capabilities.

#### `gov.nist.javax.sdp.fields`
SDP field implementations for parsing and constructing SDP message components including media descriptions, connection information, and session attributes.

#### `gov.nist.javax.sdp.parser`
Parser components for SDP message processing, enabling robust parsing and validation of SDP content in SIP messages.

#### `javax.sdp`
Standard Java SDP API interfaces and contracts, providing the foundational API layer for SDP operations.

### Vorpal Blade Framework

#### `org.vorpal.blade.framework.v2`
Core framework classes providing the main application infrastructure, base classes for SIP applications, and fundamental framework services.

#### `org.vorpal.blade.framework.v2.analytics`
Analytics framework components for collecting, processing, and reporting application metrics, call statistics, and performance data.

#### `org.vorpal.blade.framework.v2.b2bua`
Back-to-Back User Agent implementation providing call bridging, media handling, and advanced call control capabilities for building proxy and gateway services.

#### `org.vorpal.blade.framework.v2.callflow`
Call flow management components for defining, executing, and monitoring complex SIP call scenarios and service logic workflows.

#### `org.vorpal.blade.framework.v2.config`
Configuration management framework providing dynamic configuration loading, validation, and runtime configuration updates for applications.

#### `org.vorpal.blade.framework.v2.keepalive`
Keep-alive and health monitoring components for maintaining service availability and implementing heartbeat mechanisms.

#### `org.vorpal.blade.framework.v2.logging`
Enhanced logging framework with SIP-aware logging capabilities, structured logging, and integration with enterprise logging systems.

#### `org.vorpal.blade.framework.v2.proxy`
SIP proxy implementation components providing routing, load balancing, and message forwarding capabilities for building SIP proxy services.

#### `org.vorpal.blade.framework.v2.testing`
Testing framework and utilities for unit testing SIP applications, mock SIP endpoints, and integration testing support.

#### `org.vorpal.blade.framework.v2.transfer`
Call transfer implementation providing supervised and unsupervised transfer capabilities, transfer state management, and REFER method handling.

#### `org.vorpal.blade.framework.v2.transfer.api`
API interfaces and contracts for call transfer operations, enabling extensible transfer implementations and custom transfer logic.

## Dependencies

| Dependency | Purpose |
|------------|---------|
| `com.fasterxml.jackson.dataformat:jackson-dataformat-xml` | XML serialization and deserialization for configuration and data exchange |
| `com.fasterxml.jackson.core:jackson-databind` | JSON data binding and object mapping for API and configuration processing |
| `org.slf4j:slf4j-api` | Logging facade providing unified logging interface across the framework |
| `com.kjetland:mbknor-jackson-jsonschema_2.13` | JSON Schema generation for configuration validation and API documentation |
| `io.swagger.core.v3:swagger-jaxrs2` | OpenAPI documentation generation for REST APIs and service interfaces |
| `com.jayway.jsonpath:json-path` | JSON path expressions for configuration queries and data extraction |
| `com.github.seancfoley:ipaddress` | IP address parsing, validation, and manipulation utilities |
| `org.apache.commons:commons-collections4` | Enhanced collection utilities for data processing and manipulation |
| `org.apache.commons:commons-email` | Email utilities for notifications and administrative communications |

## Related Modules

### Core Libraries
- [libs/shared/bin](../libs/shared/bin) - Shared utilities and common components
- [libs/fsmar](../libs/fsmar) - FSMAR protocol implementation

### Administration
- [admin/console](../admin/console) - Administrative web console
- [admin/configurator](../admin/configurator) - Configuration management interface

### Services
- [services/acl](../services/acl) - Access Control List service
- [services/analytics](../services/analytics) - Analytics and reporting service
- [services/hold](../services/hold) - Call hold management service
- [services/options](../services/options) - SIP OPTIONS handling service
- [services/presence](../services/presence) - Presence and subscription service
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing proxy service
- [services/proxy-block](../services/proxy-block) - Call blocking proxy service
- [services/proxy-registrar](../services/proxy-registrar) - SIP registrar service
- [services/proxy-router](../services/proxy-router) - Routing proxy service
- [services/queue](../services/queue) - Call queue management service
- [services/tpcc](../services/tpcc) - Third Party Call Control service
- [services/transfer](../services/transfer) - Call transfer service

## Integration Guide

### Basic Framework Usage

1. **Extend Framework Classes**: Inherit from core framework classes in `org.vorpal.blade.framework.v2` to build SIP applications
2. **Configure Services**: Use configuration framework components to define service behavior and parameters
3. **Implement Call Logic**: Utilize callflow components to define service-specific SIP message handling
4. **Add Analytics**: Integrate analytics components to monitor and report service performance

### Service Development

1. **Service Base Classes**: Use proxy or B2BUA base classes depending on service requirements
2. **Configuration Schema**: Define JSON schemas for service configuration using the configuration framework
3. **Logging Integration**: Implement structured logging using the framework's logging components
4. **Testing Framework**: Utilize testing components for comprehensive service validation

### Deployment Considerations

- Framework provides the base infrastructure for all Vorpal Blade services
- Configuration management enables runtime service customization
- Analytics framework supports operational monitoring and troubleshooting
- Keep-alive components ensure service availability and health monitoring