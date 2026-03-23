# TPCC Service Module

## Overview

The TPCC (Third Party Call Control) service module provides advanced call control capabilities for SIP-based telecommunications applications. This module implements sophisticated call flow management, session handling, and dialog control mechanisms to enable third-party applications to manage and control call sessions between endpoints.

## Architecture

The module follows a layered architecture with versioned APIs, call flow management, and session/dialog control components:

- **Core Service Layer**: Base TPCC service implementation and configuration
- **Call Flows**: Pre-defined call flow templates and management
- **API Layer**: Versioned interfaces (v1) for external integration
- **Dialog Management**: SIP dialog state management and control
- **Session Management**: Call session lifecycle and state handling

## Packages

### [`org.vorpal.blade.services.tpcc`](#orgvorpalbladeservicestpcc)
Core TPCC service implementation containing the main service classes, configuration management, and base functionality for third-party call control operations.

### [`org.vorpal.blade.services.tpcc.callflows`](#orgvorpalbladeservicestpcccallflows)
Call flow orchestration and management components that define and execute standardized call control patterns such as click-to-call, call transfer, and conference bridging scenarios.

### [`org.vorpal.blade.services.tpcc.v1`](#orgvorpalbladeservicestpccv1)
Version 1 API interfaces and implementations providing external access to TPCC functionality through well-defined service contracts and data transfer objects.

### [`org.vorpal.blade.services.tpcc.v1.dialog`](#orgvorpalbladeservicestpccv1dialog)
SIP dialog management components for the v1 API, handling dialog state transitions, dialog correlation, and dialog-specific operations within call control scenarios.

### [`org.vorpal.blade.services.tpcc.v1.session`](#orgvorpalbladeservicestpccv1session)
Session management implementation for v1 API, providing call session lifecycle management, session state persistence, and session correlation across multiple dialogs.

## Dependencies

### Core Dependencies
- **org.vorpal.blade:vorpal-blade-library-framework**: Core framework providing SIP servlet infrastructure, service lifecycle management, and common utilities for Vorpal Blade services

## Related Modules

### Framework & Libraries
- [libs/framework](../libs/framework) - Core framework components and utilities
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities and helpers
- [libs/fsmar](../libs/fsmar) - Finite State Machine and Action Router library

### Administrative Modules
- [admin/console](../admin/console) - Administrative web console interface
- [admin/configurator](../admin/configurator) - Service configuration management

### Service Modules
- [services/acl](../services/acl) - Access Control List service for authorization
- [services/analytics](../services/analytics) - Call analytics and reporting service
- [services/hold](../services/hold) - Call hold functionality
- [services/options](../services/options) - SIP OPTIONS handling service
- [services/presence](../services/presence) - Presence and availability service
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing proxy service
- [services/proxy-block](../services/proxy-block) - Call blocking proxy service
- [services/proxy-registrar](../services/proxy-registrar) - SIP registration proxy
- [services/proxy-router](../services/proxy-router) - SIP routing proxy service
- [services/queue](../services/queue) - Call queuing and distribution service
- [services/transfer](../services/transfer) - Call transfer service

## Integration Guide

### Maven Configuration

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.vorpal.blade</groupId>
    <artifactId>services-tpcc</artifactId>
    <version>${vorpal.blade.version}</version>
</dependency>
```

### Service Integration

1. **Service Registration**: The TPCC service automatically registers with the Vorpal Blade framework upon deployment
2. **API Access**: Use the v1 API interfaces for external integration and call control operations
3. **Call Flow Configuration**: Configure custom call flows through the admin console or configuration files
4. **Session Management**: Leverage session management APIs for maintaining call state across dialog boundaries

### Configuration

The service supports configuration through:
- Environment variables
- Configuration files in the deployment directory
- Runtime configuration via the admin console
- Programmatic configuration through the v1 API

## Features

- Third-party call initiation and termination
- Multi-party call conferencing
- Call transfer and forwarding
- Session state management
- SIP dialog correlation
- Configurable call flow templates
- RESTful API integration
- Real-time call control events