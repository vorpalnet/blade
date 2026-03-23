# Framework Module

The **libs/framework** module provides the core infrastructure and utilities for the Vorpal Blade SIP application server. This module contains essential components for SIP session management, B2BUA functionality, call flow orchestration, configuration management, and testing utilities, along with integrated NIST SDP (Session Description Protocol) libraries.

## Overview

This framework module serves as the foundation for building robust SIP-based applications and services. It provides a comprehensive set of tools and abstractions that simplify the development of telecommunications applications while maintaining high performance and reliability standards.

Key features include:
- Advanced B2BUA (Back-to-Back User Agent) capabilities
- Flexible call flow management and orchestration
- Comprehensive analytics and monitoring
- Configuration management with JSON schema validation
- Proxy and transfer functionalities
- Testing utilities for SIP applications
- Full SDP parsing and manipulation support

## Architecture

The module is organized into several key functional areas:

### Core Framework
Provides the foundational classes and utilities for SIP application development.

### SIP Protocol Support
Includes complete SDP (Session Description Protocol) implementation from NIST libraries for robust SIP message handling.

### Application Services
Higher-level services for common telecommunications use cases including B2BUA operations, call transfers, and proxy functionality.

## Package Structure

### NIST SDP Libraries

#### `gov.nist.core`
Core NIST utilities and foundational classes for SIP protocol implementation. Provides essential data structures and helper methods used throughout the SDP stack.

#### `javax.sdp`
Standard Java SDP API definitions and interfaces. This package defines the public API for Session Description Protocol operations in compliance with JSR standards.

#### `gov.nist.javax.sdp`
NIST's implementation of the standard SDP API. Contains concrete implementations of the javax.sdp interfaces with enhanced functionality and performance optimizations.

#### `gov.nist.javax.sdp.fields`
SDP field implementations for all standard and extended SDP attributes. Handles parsing, validation, and serialization of individual SDP message components.

#### `gov.nist.javax.sdp.parser`
Comprehensive SDP message parsing engine. Provides robust parsing capabilities for SDP messages with error handling and validation.

### Vorpal Blade Framework

#### `org.vorpal.blade.framework.v2`
Core framework classes and base abstractions for SIP servlet applications. Contains fundamental building blocks for creating SIP-based services.

#### `org.vorpal.blade.framework.v2.analytics`
Analytics and metrics collection framework for monitoring SIP application performance, call statistics, and system health.

#### `org.vorpal.blade.framework.v2.b2bua`
Back-to-Back User Agent implementation providing call bridging, media relay, and session management capabilities for advanced call routing scenarios.

#### `org.vorpal.blade.framework.v2.callflow`
Call flow orchestration engine for defining and executing complex telecommunications workflows with state management and error handling.

#### `org.vorpal.blade.framework.v2.config`
Configuration management system with support for JSON schema validation, dynamic reloading, and environment-specific settings.

#### `org.vorpal.blade.framework.v2.keepalive`
Keep-alive and health monitoring utilities for maintaining persistent connections and ensuring service availability.

#### `org.vorpal.blade.framework.v2.logging`
Enhanced logging framework with SIP-specific log formatting, call correlation, and structured logging capabilities.

#### `org.vorpal.blade.framework.v2.proxy`
SIP proxy implementation with routing logic, load balancing, and failover capabilities for scalable SIP infrastructure.

#### `org.vorpal.blade.framework.v2.testing`
Comprehensive testing utilities including SIP message generators, mock objects, and test harnesses for unit and integration testing.

#### `org.vorpal.blade.framework.v2.transfer`
Call transfer implementation supporting both attended and unattended transfers with comprehensive error handling and state management.

#### `org.vorpal.blade.framework.v2.transfer.api`
Public API definitions for call transfer operations, providing clean interfaces for transfer functionality integration.

## Dependencies

### Core Libraries
- **com.fasterxml.jackson.dataformat:jackson-dataformat-xml** - XML data binding for configuration and message processing
- **com.fasterxml.jackson.core:jackson-databind** - JSON data binding and object mapping
- **org.slf4j:slf4j-api** - Logging facade for consistent logging across the framework

### Schema and Documentation
- **com.kjetland:mbknor-jackson-jsonschema_2.13** - JSON schema generation for configuration validation
- **io.swagger.core.v3:swagger-jaxrs2** - API documentation and OpenAPI specification support

### Utilities
- **com.jayway.jsonpath:json-path** - JSONPath expression evaluation for configuration querying
- **com.github.seancfoley:ipaddress** - Advanced IP address parsing and manipulation
- **org.apache.commons:commons-collections4** - Enhanced collection utilities and data structures
- **org.apache.commons:commons-email** - Email notification capabilities for system alerts

## Related Modules

- [**libs/shared**](../shared) - Shared utilities and common classes used across all modules
- [**libs/fsmar**](../fsmar) - Finite State Machine and Application Router implementation
- [**admin/console**](../admin/console) - Administrative web console for system management
- [**admin/configurator**](../admin/configurator) - Configuration management interface
- [**services**](../services) - Application services built on this framework

## Integration Guide

### Basic Usage

1. **Extend Framework Classes**: Inherit from base framework classes to create SIP servlets
2. **Configure Services**: Use the configuration management system to define service parameters
3. **Implement Call Flows**: Leverage the call flow engine to define business logic
4. **Add Analytics**: Integrate analytics components for monitoring and reporting

### Configuration

The framework uses JSON-based configuration with schema validation. Configuration files should be structured according to the JSON schemas provided by the config package.

### Testing

Utilize the testing utilities to create comprehensive test suites for SIP applications. The testing framework provides mock SIP environments and message generators for thorough testing scenarios.

### Logging

Configure logging using the enhanced logging framework which provides SIP-specific formatting and correlation capabilities for better debugging and monitoring.

## Build Requirements

- Java 8 or higher
- Maven 3.6+
- Access to required external dependencies

This module is designed to be the cornerstone of SIP application development within the Vorpal Blade ecosystem, providing both low-level protocol support and high-level application services.