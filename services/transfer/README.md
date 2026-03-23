# Transfer Service Module

The `services/transfer` module provides SIP call transfer functionality within the Vorpal Blade platform. This service handles both attended and unattended call transfers, managing the complex SIP signaling required to seamlessly move active calls between endpoints.

## Overview

This module implements RFC 3515 (REFER method) and related SIP transfer specifications to enable:

- **Attended Transfers**: Consultation-based transfers where the transferor speaks with the transfer target before completing the transfer
- **Unattended Transfers**: Blind transfers where calls are immediately redirected to the target
- **Transfer State Management**: Tracking transfer progress and handling failure scenarios
- **Multi-party Transfer Support**: Advanced transfer scenarios involving multiple participants

The service integrates with the broader Vorpal Blade ecosystem to provide enterprise-grade call transfer capabilities with full audit trails and analytics support.

## Package Structure

### [`org.vorpal.blade.services.transfer`](#orgvorpalbladeservicestransfer)

Core transfer service implementation containing:

- **Transfer Controllers**: Main service logic for handling REFER requests and managing transfer workflows
- **Transfer State Machines**: Stateful processing of transfer operations with proper cleanup
- **SIP Message Handlers**: Protocol-specific handlers for REFER, NOTIFY, and related methods
- **Transfer Validators**: Business rule validation for transfer authorization and feasibility
- **Event Publishers**: Integration points for transfer-related events and notifications

## Dependencies

### Core Dependencies

- **org.vorpal.blade:vorpal-blade-library-framework**: Core framework providing SIP servlet infrastructure, session management, and common utilities required for service operation

## Related Modules

### Framework & Shared Libraries
- [**libs/framework**](../libs/framework) - Core framework components and utilities
- [**libs/shared/bin**](../libs/shared/bin) - Shared binary utilities and common functions
- [**libs/fsmar**](../libs/fsmar) - Finite State Machine and Rules engine

### Administration
- [**admin/console**](../admin/console) - Web-based administration interface
- [**admin/configurator**](../admin/configurator) - Configuration management tools

### Related Services
- [**services/acl**](../services/acl) - Access control and authorization services
- [**services/analytics**](../services/analytics) - Call analytics and reporting integration
- [**services/hold**](../services/hold) - Call hold/unhold functionality that works with transfers
- [**services/options**](../services/options) - SIP OPTIONS handling for capability negotiation
- [**services/presence**](../services/presence) - Presence information for transfer target validation
- [**services/proxy-balancer**](../services/proxy-balancer) - Load balancing for transfer routing
- [**services/proxy-block**](../services/proxy-block) - Call blocking rules that may affect transfers
- [**services/proxy-registrar**](../services/proxy-registrar) - User registration for transfer target resolution
- [**services/proxy-router**](../services/proxy-router) - Call routing logic for transfer completion
- [**services/queue**](../services/queue) - Call queue integration for transfer-to-queue scenarios
- [**services/tpcc**](../services/tpcc) - Third-party call control integration

## Integration Guide

### Service Configuration

1. **Enable Transfer Service**: Configure the service in your Vorpal Blade deployment descriptor
2. **ACL Integration**: Set up appropriate access controls through the ACL service
3. **Analytics Setup**: Configure transfer event reporting to the analytics service
4. **Routing Rules**: Establish transfer routing policies via the proxy-router service

### Key Integration Points

- **Session Management**: Leverages framework session handling for transfer state persistence
- **Event Notification**: Publishes transfer events to analytics and audit systems  
- **Authorization**: Validates transfer permissions through ACL service integration
- **Target Resolution**: Uses registrar and presence services for transfer target validation
- **Call State Coordination**: Integrates with hold service for proper call state management

### Deployment Considerations

- Ensure proper SIP dialog state management across transfer operations
- Configure appropriate timeouts for transfer completion scenarios
- Set up monitoring for transfer success/failure rates
- Establish proper cleanup procedures for failed transfers