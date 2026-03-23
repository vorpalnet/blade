# Admin Console Module

A comprehensive web-based administration console for the Vorpal Blade framework, providing graphical configuration management, monitoring, and system administration capabilities.

## Overview

The `admin/console` module serves as the primary administrative interface for Vorpal Blade applications. It provides a web-based console with visual graph editing capabilities, configuration management, and real-time monitoring of framework components and services.

Key features:
- Web-based administration interface
- Visual graph-based configuration editing using mxGraph
- RESTful API with Swagger documentation
- Configuration validation and testing utilities
- Integration with multiple Vorpal Blade services

## Architecture

This module is built on a layered architecture consisting of:
- **Web Layer**: JSP-based web application with REST endpoints
- **Configuration Layer**: Management and validation of system configurations
- **Graph Layer**: Visual representation and editing of service topologies
- **Utilities Layer**: Supporting tools and mxGraph extensions

## Packages

### [`com.mxgraph.util`](#commxgraphutil)
Extended utilities and enhancements for the mxGraph library, providing additional functionality for graph manipulation and rendering within the admin console context.

### [`org.vorpal.blade.applications.console.config`](#orgvorpalbladeapplicationsconsoleconfig)
Core configuration management classes responsible for loading, validating, and persisting administrative console settings and system configurations.

### [`org.vorpal.blade.applications.console.config.test`](#orgvorpalbladeapplicationsconsoleconfigtest)
Testing utilities and validation frameworks for configuration management, ensuring configuration integrity and providing diagnostic capabilities.

### [`org.vorpal.blade.applications.console.mxgraph`](#orgvorpalbladeapplicationsconsolemxgraph)
Integration layer between the mxGraph visualization library and Vorpal Blade framework, enabling visual representation of service topologies and call flows.

### [`org.vorpal.blade.applications.console.webapp`](#orgvorpalbladeapplicationsconsolewebapp)
Web application components including servlets, REST endpoints, and web utilities that power the administrative console's user interface and API.

### [`org.vorpal.blade.framework.v2.config`](#orgvorpalbladeframeworkv2config)
Framework-level configuration abstractions and interfaces that define the configuration contracts used throughout the Vorpal Blade ecosystem.

## Dependencies

### Core Dependencies
- **org.vorpal.blade:vorpal-blade-library-framework** - Core framework libraries and utilities
- **org.hsqldb:hsqldb** - Embedded database for configuration storage and caching

### Web & API Dependencies
- **jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api** - JSP Standard Tag Library for web interface
- **io.swagger.core.v3:swagger-jaxrs2** - API documentation and REST endpoint generation
- **io.swagger.core.v3:swagger-jaxrs2-servlet-initializer-v2** - Swagger servlet integration

### Visualization Dependencies
- **com.github.vlsi.mxgraph:jgraphx** - Graph visualization and editing capabilities
- **org.apache.xmlgraphics:fop** - PDF and document generation for reports

### Utility Dependencies
- **commons-fileupload:commons-fileupload** (v1.5) - File upload handling for configuration imports
- **commons-lang:commons-lang** (v2.6) - Common utility functions and string manipulation

## Related Modules

### Framework Libraries
- [libs/framework](../libs/framework) - Core framework components
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities
- [libs/fsmar](../libs/fsmar) - Finite State Machine Archive utilities

### Administrative Tools
- [admin/configurator](../admin/configurator) - Advanced configuration management tools

### Managed Services
The console provides management capabilities for the following services:

**Core Services:**
- [services/acl](../services/acl) - Access Control Lists
- [services/analytics](../services/analytics) - Call analytics and reporting
- [services/presence](../services/presence) - Presence management

**Proxy Services:**
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing
- [services/proxy-block](../services/proxy-block) - Call blocking and filtering
- [services/proxy-registrar](../services/proxy-registrar) - SIP registration handling
- [services/proxy-router](../services/proxy-router) - Call routing logic

**Call Handling Services:**
- [services/hold](../services/hold) - Call hold management
- [services/options](../services/options) - SIP OPTIONS handling
- [services/queue](../services/queue) - Call queuing system
- [services/transfer](../services/transfer) - Call transfer services

**Specialized Services:**
- [services/tpcc](../services/tpcc) - Third Party Call Control

## Integration Guide

### Basic Setup

1. **Deploy the Web Application**: Deploy the admin console WAR to your servlet container
2. **Configure Database**: Ensure HSQLDB is properly configured for configuration persistence
3. **Framework Integration**: Verify connection to the Vorpal Blade framework libraries

### Configuration Management

The console integrates with the framework's configuration system through:
- RESTful configuration APIs
- Visual graph-based configuration editing
- Real-time validation and testing utilities
- Import/export capabilities for configuration sets

### Service Integration

Each managed service integrates with the console through:
- Standardized configuration interfaces
- Status monitoring endpoints
- Administrative control APIs
- Visual representation in service topology graphs

## API Documentation

When deployed, the admin console provides interactive API documentation via Swagger UI, accessible at `/api-docs` endpoint. This includes comprehensive documentation for all REST endpoints and configuration schemas.