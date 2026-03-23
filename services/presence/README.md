# Presence Service Module

## Overview

The `services/presence` module provides presence management capabilities for the Vorpal Blade SIP communication platform. This service tracks and manages the real-time availability status of users, endpoints, and network entities within the SIP ecosystem. It enables presence-aware routing, status publishing/subscribing, and integration with various communication services.

## Architecture

This module implements SIP SIMPLE (Session Initiation Protocol for Instant Messaging and Presence Leveraging Extensions) standards to provide comprehensive presence functionality including:

- Real-time presence state management
- Presence publication and subscription handling
- Watcher list management
- Presence policy enforcement
- Integration with SIP registrar services

## Packages

### org.vorpal.blade.services.presence

The core presence service package containing:

- **Presence State Management** - Tracks and maintains user presence information
- **Publication Handlers** - Processes presence publication requests from endpoints  
- **Subscription Management** - Handles presence subscription requests and notifications
- **Watcher Services** - Manages presence watcher lists and permissions
- **Policy Engine** - Enforces presence authorization policies
- **Event Notification** - Distributes presence updates to subscribers

## Dependencies

### Core Dependencies

- **org.vorpal.blade:vorpal-blade-library-framework** - Core framework providing SIP servlet container, message handling, and service lifecycle management

### Related Framework Modules

- [**libs/framework**](../libs/framework) - Base framework components and utilities
- [**libs/shared/bin**](../libs/shared/bin) - Shared binary utilities and common functions
- [**libs/fsmar**](../libs/fsmar) - Finite State Machine framework for presence state transitions

### Administrative Modules

- [**admin/console**](../admin/console) - Web-based administration interface for presence configuration
- [**admin/configurator**](../admin/configurator) - Configuration management tools

### Service Integration

- [**services/acl**](../services/acl) - Access control lists for presence authorization
- [**services/analytics**](../services/analytics) - Presence metrics and reporting
- [**services/proxy-registrar**](../services/proxy-registrar) - SIP registration integration for presence binding
- [**services/proxy-router**](../services/proxy-router) - Presence-aware call routing
- [**services/proxy-balancer**](../services/proxy-balancer) - Load balancing with presence considerations

### Supporting Services

- [**services/hold**](../services/hold) - Call hold status integration
- [**services/options**](../services/options) - SIP OPTIONS method handling for capability discovery
- [**services/proxy-block**](../services/proxy-block) - Blocking services integration
- [**services/queue**](../services/queue) - Message queuing for presence events
- [**services/tpcc**](../services/tpcc) - Third-party call control integration
- [**services/transfer**](../services/transfer) - Call transfer status tracking

## Integration Guide

### Basic Setup

1. **Module Configuration**: Configure presence service parameters including subscription timeouts, notification intervals, and policy rules
2. **Database Integration**: Set up presence state persistence if required for high-availability deployments
3. **SIP Integration**: Ensure proper integration with SIP registrar and proxy services for presence-aware routing

### Service Integration

The presence service integrates with other Vorpal Blade services through:

- **Event Subscription**: Other services can subscribe to presence state changes
- **Policy Integration**: ACL service provides authorization rules for presence access
- **Analytics Integration**: Presence metrics are reported to the analytics service
- **Routing Integration**: Proxy services utilize presence information for intelligent call routing

### Deployment Considerations

- Configure appropriate memory allocation for presence state caching
- Set up clustering support for distributed presence state management
- Implement proper security policies for presence information access
- Consider geographic distribution requirements for presence replication

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>services-presence</artifactId>
```

## Version Compatibility

This module is designed to work with the Vorpal Blade platform and requires compatible versions of the framework and related service modules.