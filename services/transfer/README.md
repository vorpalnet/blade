# Transfer Service Module

## Overview

The Transfer Service module provides comprehensive call transfer functionality within the Vorpal Blade telecommunications platform. This module implements SIP-compliant call transfer mechanisms, including supervised and unsupervised (blind) transfers, consultative transfers, and advanced transfer scenarios. It manages the complex state transitions and SIP dialog manipulations required for seamless call handoffs between endpoints.

## Module Information

- **Module Name**: `services/transfer`
- **Group ID**: `org.vorpal.blade`
- **Artifact ID**: `vorpal-blade-services-transfer`
- **Type**: Service Module

## Packages

### `org.vorpal.blade.services.transfer`

Core transfer service implementation containing:

- **Transfer Controllers**: Manage different types of call transfers (blind, attended, consultative)
- **State Machines**: Handle complex transfer state transitions and SIP dialog management
- **Transfer Handlers**: Process SIP REFER messages and manage transfer workflows
- **Session Management**: Track and coordinate multiple SIP dialogs during transfer operations
- **Event Processors**: Handle transfer-related SIP events and notifications

## Dependencies

### Core Dependencies

- **`org.vorpal.blade:vorpal-blade-library-framework`**: Provides the foundational framework components, SIP stack integration, and core service infrastructure required for implementing transfer operations

## Related Modules

### Framework and Shared Libraries
- [**libs/framework**](../libs/framework) - Core framework and SIP stack integration
- [**libs/shared/bin**](../libs/shared/bin) - Shared utilities and binary components
- [**libs/fsmar**](../libs/fsmar) - Finite State Machine and Routing utilities

### Administration Modules
- [**admin/console**](../admin/console) - Management console with transfer monitoring capabilities
- [**admin/configurator**](../admin/configurator) - Transfer service configuration management

### Service Modules
- [**services/acl**](../services/acl) - Access control for transfer operations
- [**services/analytics**](../services/analytics) - Transfer operation analytics and reporting
- [**services/hold**](../services/hold) - Call hold functionality used during consultative transfers
- [**services/options**](../services/options) - SIP OPTIONS handling for transfer capability negotiation
- [**services/presence**](../services/presence) - Presence information for transfer target validation
- [**services/proxy-balancer**](../services/proxy-balancer) - Load balancing for transfer routing
- [**services/proxy-block**](../services/proxy-block) - Call blocking integration for transfer restrictions
- [**services/proxy-registrar**](../services/proxy-registrar) - User location services for transfer targets
- [**services/proxy-router**](../services/proxy-router) - Routing services for transfer destinations
- [**services/queue**](../services/queue) - Queue integration for transferred calls
- [**services/tpcc**](../services/tpcc) - Third-party call control coordination

## Features

- **Blind Transfer**: Immediate call handoff without consultation
- **Attended Transfer**: Consultation with transfer target before completion
- **Consultative Transfer**: Multi-party consultation before transfer execution
- **Transfer Recovery**: Handling of failed transfer scenarios and fallback procedures
- **SIP REFER Support**: Full RFC 3515 compliance for SIP-based transfers
- **State Persistence**: Reliable transfer state management across system restarts
- **Multi-party Coordination**: Complex transfer scenarios involving multiple parties

## Integration Guide

### Basic Setup

1. **Dependency Configuration**: Include the transfer service module in your Maven dependencies
2. **Service Registration**: Register transfer handlers with the SIP application router
3. **State Management**: Configure transfer state persistence and recovery mechanisms
4. **Event Handling**: Set up transfer event listeners and notification handlers

### Service Integration

The Transfer Service integrates with multiple Vorpal Blade components:

- **Proxy Services**: Coordinates with routing and registration services for transfer target resolution
- **Hold Service**: Manages call hold states during consultative transfers
- **Analytics Service**: Provides transfer operation metrics and success rates
- **Queue Service**: Handles transfers to queue destinations and agent endpoints

### Configuration Requirements

- Transfer timeout configurations for different transfer types
- SIP dialog management parameters for multi-party scenarios
- Integration endpoints for related services (hold, queue, routing)
- Security policies for transfer authorization and validation

## Usage Notes

- Requires active SIP dialogs for transfer operations
- Integrates with presence services for transfer target availability
- Supports both SIP REFER and application-layer transfer mechanisms
- Provides comprehensive logging for transfer operation debugging and audit trails