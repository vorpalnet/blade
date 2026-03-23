# Presence Service

[![Maven](https://img.shields.io/badge/Maven-Module-blue.svg)](https://maven.apache.org/)
[![Java](https://img.shields.io/badge/Java-Enterprise-orange.svg)](https://www.oracle.com/java/)

## Overview

The Presence Service module provides real-time user presence management and notification capabilities within the Vorpal Blade communication platform. This service handles presence state tracking, subscription management, and presence event distribution across the system.

Key features include:
- Real-time presence state monitoring
- Presence subscription and notification management
- Integration with SIP/SIMPLE presence framework
- Scalable presence event distribution
- Presence policy enforcement and privacy controls

## Architecture

This module implements presence functionality as part of the larger Vorpal Blade services ecosystem, providing centralized presence management for communication services including voice, video, and messaging applications.

## Packages

### [`org.vorpal.blade.services.presence`](#orgvorpalbladeservicespresence)

Core presence service implementation containing:
- **Presence State Management** - Tracks and manages user presence states (available, busy, away, offline)
- **Subscription Handling** - Manages presence subscription requests and authorization
- **Notification Engine** - Distributes presence updates to authorized subscribers
- **Policy Enforcement** - Implements presence privacy and access control policies
- **Event Processing** - Handles incoming presence publications and state changes

## Dependencies

| Dependency | Purpose |
|------------|---------|
| `org.vorpal.blade:vorpal-blade-library-framework` | Core framework providing base services, configuration management, and common utilities |

## Related Modules

### Core Framework
- [libs/framework](../libs/framework) - Core framework and base services
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities and common components
- [libs/fsmar](../libs/fsmar) - Finite State Machine and Routing library

### Administration
- [admin/console](../admin/console) - Administrative console for presence service management
- [admin/configurator](../admin/configurator) - Configuration management tools

### Service Modules
- [services/acl](../services/acl) - Access Control Lists for presence authorization
- [services/analytics](../services/analytics) - Presence analytics and reporting
- [services/proxy-registrar](../services/proxy-registrar) - User registration and location services
- [services/proxy-router](../services/proxy-router) - Message routing and delivery
- [services/options](../services/options) - SIP OPTIONS handling for presence capabilities

### Supporting Services
- [services/hold](../services/hold) - Call hold state integration
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing for presence traffic
- [services/proxy-block](../services/proxy-block) - Traffic filtering and blocking
- [services/queue](../services/queue) - Message queuing for presence events
- [services/tpcc](../services/tpcc) - Third-party call control integration
- [services/transfer](../services/transfer) - Call transfer state coordination

## Integration Guide

### Service Configuration

1. **Presence Policies** - Configure presence privacy and access control policies through the [admin/configurator](../admin/configurator) module
2. **ACL Integration** - Coordinate with [services/acl](../services/acl) for subscriber authorization
3. **Analytics Setup** - Enable presence metrics collection via [services/analytics](../services/analytics)

### Runtime Dependencies

- Requires active [services/proxy-registrar](../services/proxy-registrar) for user location services
- Integrates with [services/proxy-router](../services/proxy-router) for message delivery
- Utilizes [services/queue](../services/queue) for asynchronous event processing

### Deployment Notes

- Deploy alongside core proxy services for optimal performance
- Configure appropriate scaling parameters for high-presence-volume environments
- Ensure network connectivity to all related service modules for full functionality

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>services-presence</artifactId>
```

## License

This module is part of the Vorpal Blade communication platform. See project root for license information.