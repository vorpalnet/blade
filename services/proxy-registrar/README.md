# Proxy Registrar Service

The `services/proxy-registrar` module provides SIP registration management capabilities for the Vorpal Blade platform. This service handles user agent registration, maintains registration state, and manages contact bindings for SIP proxy operations.

## Overview

The Proxy Registrar service implements RFC 3261 compliant SIP registration functionality, enabling user agents to register their contact information with the SIP proxy server. It manages registration expiration, authentication challenges, and contact binding updates while integrating seamlessly with other Vorpal Blade services.

Key features:
- SIP REGISTER request processing and validation
- Contact binding management and storage
- Registration expiration handling
- Authentication integration
- Real-time registration status monitoring
- High availability and failover support

## Architecture

This module follows the Vorpal Blade service architecture pattern and integrates with the framework's servlet container, configuration management, and persistence layers. The registrar maintains an in-memory cache of active registrations while persisting long-term state for reliability.

## Packages

### [`org.vorpal.blade.services.proxy.registrar.v3`](#orgvorpalbladeservicesproxyregistrarv3)

Core implementation of the SIP registrar functionality including:
- Registration request processing
- Contact binding management
- Expiration timer handling
- Database persistence operations
- Administrative interfaces

## Dependencies

### Core Dependencies

- **org.vorpal.blade:vorpal-blade-library-framework** - Core framework providing servlet container, configuration management, logging, and base service infrastructure

### Related Framework Modules

- [**libs/framework**](../libs/framework) - Base framework components and utilities
- [**libs/shared/bin**](../libs/shared/bin) - Shared binary utilities and helper classes
- [**libs/fsmar**](../libs/fsmar) - Finite State Machine and Application Router components

### Administration Modules

- [**admin/console**](../admin/console) - Web-based administration console for monitoring registration status
- [**admin/configurator**](../admin/configurator) - Configuration management interface for registrar settings

### Integrated Services

- [**services/acl**](../services/acl) - Access control list management for registration authorization
- [**services/analytics**](../services/analytics) - Registration metrics and analytics collection
- [**services/proxy-router**](../services/proxy-router) - Request routing based on registration data
- [**services/proxy-balancer**](../services/proxy-balancer) - Load balancing for registered endpoints
- [**services/proxy-block**](../services/proxy-block) - Blocking and filtering integration
- [**services/presence**](../services/presence) - Presence status tied to registration state

### Supporting Services

- [**services/hold**](../services/hold) - Call hold functionality for registered endpoints
- [**services/options**](../services/options) - SIP OPTIONS handling for registered contacts
- [**services/queue**](../services/queue) - Message queuing for registration events
- [**services/tpcc**](../services/tpcc) - Third-party call control integration
- [**services/transfer**](../services/transfer) - Call transfer capabilities

## Configuration

The registrar service requires configuration for:
- Default registration expiration times
- Maximum contacts per Address of Record (AOR)
- Database connection parameters
- Authentication realm settings
- Integration endpoints for related services

Configuration is managed through the standard Vorpal Blade configuration framework accessible via the admin console.

## Integration Guide

### Basic Integration

1. Ensure the framework and required dependencies are properly configured
2. Deploy the proxy-registrar service to the Vorpal Blade container
3. Configure database connectivity for registration persistence
4. Set up authentication integration if required
5. Configure related services (ACL, analytics, etc.) as needed

### Service Interaction

The registrar service automatically integrates with:
- **ACL Service** for registration authorization
- **Analytics Service** for metrics collection  
- **Proxy Router** for location service functionality
- **Presence Service** for registration status updates

### Monitoring

Registration status and metrics are available through:
- Admin console dashboard
- Analytics service integration
- JMX monitoring endpoints
- Log file analysis

## API Compatibility

This module implements version 3 of the proxy registrar API, maintaining backward compatibility with previous versions while providing enhanced functionality and performance improvements.