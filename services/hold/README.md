# services/hold

A Maven module providing call hold functionality for VoIP services within the Vorpal Blade framework.

## Overview

The `services/hold` module implements SIP-based call hold and resume capabilities, allowing users to temporarily suspend active calls and retrieve them later. This service manages the signaling and media flow control required for standards-compliant call hold operations in telecommunications environments.

## Features

- SIP INVITE with sendonly/recvonly media attribute handling
- Call state management during hold/resume operations
- Integration with media servers for hold music or announcements
- Multi-party call hold support
- Hold notification and status tracking

## Package Structure

### org.vorpal.blade.services.hold

Core package containing the call hold service implementation, including hold request processors, state managers, and SIP message handlers for managing call suspension and resumption workflows.

## Dependencies

- **org.vorpal.blade:vorpal-blade-library-framework** - Core framework providing SIP processing capabilities, service lifecycle management, and base infrastructure components

## Related Modules

### Core Framework
- [libs/framework](../libs/framework) - Base framework and SIP processing engine
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities and common components
- [libs/fsmar](../libs/fsmar) - Finite State Machine and routing components

### Administration
- [admin/console](../admin/console) - Administrative console interface
- [admin/configurator](../admin/configurator) - Configuration management tools

### Service Modules
- [services/acl](../services/acl) - Access control and authorization services
- [services/analytics](../services/analytics) - Call analytics and reporting
- [services/options](../services/options) - SIP OPTIONS handling and capability negotiation
- [services/presence](../services/presence) - User presence and availability management
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing for SIP traffic
- [services/proxy-block](../services/proxy-block) - Call blocking and filtering services
- [services/proxy-registrar](../services/proxy-registrar) - SIP registration handling
- [services/proxy-router](../services/proxy-router) - SIP message routing and forwarding
- [services/queue](../services/queue) - Call queuing and distribution
- [services/tpcc](../services/tpcc) - Third-party call control services
- [services/transfer](../services/transfer) - Call transfer and handoff functionality

## Integration

The hold service integrates with the proxy-router for call state synchronization and with analytics services for hold duration tracking. It typically works in conjunction with transfer services for advanced call management scenarios and requires proper configuration of media handling policies.

## Building

```bash
mvn clean compile
mvn package
```

## Configuration

The service requires configuration of hold music resources, timeout values, and integration endpoints through the Vorpal Blade configuration system. Refer to the admin/configurator module for configuration management details.