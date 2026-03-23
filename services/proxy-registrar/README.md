# Proxy Registrar Service

A SIP proxy registrar service module that handles user registration and location management within the Vorpal Blade SIP servlet container framework.

## Overview

The `services/proxy-registrar` module provides SIP REGISTER request processing capabilities, managing user registrations and maintaining location databases for SIP endpoints. This service is a core component of the Vorpal Blade SIP proxy infrastructure, enabling user authentication, registration validation, and contact binding management.

## Features

- SIP REGISTER request processing
- User location database management
- Contact binding and expiration handling
- Registration authentication and authorization
- Integration with proxy routing services
- Scalable registration storage backend

## Package Structure

### [`org.vorpal.blade.services.proxy.registrar.v3`](#orgvorpalbladeservicesproxyregistrarv3)

Core registrar service implementation containing:
- SIP REGISTER message handlers
- Location database interfaces and implementations
- Registration policy enforcement
- Contact management utilities
- Authentication integration components

## Dependencies

### Core Dependencies

- **`org.vorpal.blade:vorpal-blade-library-framework`** - Core framework library providing SIP servlet container functionality, base service classes, and essential utilities

## Related Modules

### Core Framework
- [**libs/framework**](../libs/framework) - Base framework components and service interfaces
- [**libs/shared/bin**](../libs/shared/bin) - Shared binary utilities and common libraries
- [**libs/fsmar**](../libs/fsmar) - Finite State Machine and Application Router components

### Administration
- [**admin/console**](../admin/console) - Administrative console interface
- [**admin/configurator**](../admin/configurator) - Service configuration management

### Related Services
- [**services/acl**](../services/acl) - Access Control List service for registration authorization
- [**services/analytics**](../services/analytics) - Registration analytics and monitoring
- [**services/proxy-router**](../services/proxy-router) - SIP proxy routing engine that uses registration data
- [**services/proxy-balancer**](../services/proxy-balancer) - Load balancing service for registered endpoints
- [**services/proxy-block**](../services/proxy-block) - Call blocking service integration
- [**services/presence**](../services/presence) - Presence service integration for registered users

### Supporting Services
- [**services/hold**](../services/hold) - Call hold functionality
- [**services/options**](../services/options) - SIP OPTIONS handling
- [**services/queue**](../services/queue) - Message queuing service
- [**services/tpcc**](../services/tpcc) - Third Party Call Control service
- [**services/transfer**](../services/transfer) - Call transfer functionality

## Integration Guide

### Basic Setup

1. **Add Maven dependency** to your project's `pom.xml`
2. **Configure registrar settings** through the admin configurator module
3. **Initialize location database** backend storage
4. **Deploy alongside proxy-router** service for complete proxy functionality

### Service Configuration

The registrar service integrates with:
- **ACL service** for registration authorization policies
- **Analytics service** for registration event tracking
- **Proxy router** for location-based call routing
- **Presence service** for user availability status

### Database Integration

Configure the location database backend through:
- Connection pool settings
- Storage retention policies
- Contact expiration timers
- Registration refresh intervals

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>proxy-registrar</artifactId>
<version>${vorpal.blade.version}</version>
```

## License

Part of the Vorpal Blade SIP Servlet Container project.