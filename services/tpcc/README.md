# TPCC Service Module

A Third Party Call Control (TPCC) service implementation for the Vorpal Blade platform, providing comprehensive call control capabilities and SIP session management.

## Overview

The TPCC (Third Party Call Control) service module enables external applications to control and manipulate SIP calls and sessions. This module implements the core functionality for managing call flows, dialog states, and session lifecycle operations in a SIP-based telecommunications environment.

## Module Structure

```
services/tpcc/
├── src/main/java/
│   └── org/vorpal/blade/services/tpcc/
│       ├── callflows/
│       └── v1/
│           ├── dialog/
│           └── session/
└── pom.xml
```

## Packages

### [`org.vorpal.blade.services.tpcc`](#orgvorpalbladeservicestpcc)

Core TPCC service implementation containing the main service classes, configuration handlers, and primary API interfaces for third-party call control operations.

### [`org.vorpal.blade.services.tpcc.callflows`](#orgvorpalbladeservicestpcccallflows)

Call flow management components that define and execute various telecommunications call scenarios, including call setup, transfer, hold, and termination workflows.

### [`org.vorpal.blade.services.tpcc.v1`](#orgvorpalbladeservicestpccv1)

Version 1 API implementation providing the public interface for TPCC operations, including REST endpoints and service contracts for external integration.

### [`org.vorpal.blade.services.tpcc.v1.dialog`](#orgvorpalbladeservicestpccv1dialog)

SIP dialog management components handling dialog state tracking, dialog lifecycle events, and dialog-specific operations within the TPCC context.

### [`org.vorpal.blade.services.tpcc.v1.session`](#orgvorpalbladeservicestpccv1session)

Session management functionality for maintaining call session state, session attributes, and coordinating multi-party call scenarios.

## Dependencies

### Core Dependencies

- **org.vorpal.blade:vorpal-blade-library-framework** - Provides the foundational framework for Vorpal Blade services, including SIP stack integration, service lifecycle management, and core utilities.

## Related Modules

### Framework & Libraries
- [libs/framework](../libs/framework) - Core framework library
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities
- [libs/fsmar](../libs/fsmar) - Finite State Machine and Action Router library

### Administration
- [admin/console](../admin/console) - Administrative console interface
- [admin/configurator](../admin/configurator) - Configuration management tools

### Service Modules
- [services/acl](../services/acl) - Access Control List service
- [services/analytics](../services/analytics) - Call analytics and reporting
- [services/hold](../services/hold) - Call hold functionality
- [services/options](../services/options) - SIP OPTIONS handling
- [services/presence](../services/presence) - Presence information management
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing proxy
- [services/proxy-block](../services/proxy-block) - Call blocking proxy
- [services/proxy-registrar](../services/proxy-registrar) - SIP registrar proxy
- [services/proxy-router](../services/proxy-router) - SIP routing proxy
- [services/queue](../services/queue) - Call queuing service
- [services/transfer](../services/transfer) - Call transfer service

## Integration

### Maven Integration

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.vorpal.blade</groupId>
    <artifactId>services-tpcc</artifactId>
    <version>${vorpal.blade.version}</version>
</dependency>
```

### Service Configuration

The TPCC service integrates with the Vorpal Blade framework's configuration system and can be configured through the admin console or configuration files. Ensure proper SIP stack configuration and network settings are in place.

### API Usage

The v1 API provides RESTful endpoints for third-party call control operations. External applications can integrate with the TPCC service to:

- Initiate and terminate calls
- Transfer calls between parties
- Manage multi-party conferences
- Monitor call states and events
- Control call flow routing

## Features

- **Third Party Call Control**: Complete SIP-based call control for external applications
- **Dialog Management**: Comprehensive SIP dialog state tracking and management
- **Session Coordination**: Multi-party call session management and coordination
- **Call Flow Orchestration**: Predefined and customizable call flow patterns
- **RESTful API**: Version-controlled REST API for easy integration
- **Event Monitoring**: Real-time call event notifications and state updates

## Build Requirements

- Java 8 or higher
- Maven 3.6+
- Access to Vorpal Blade framework dependencies