# Admin Console Module

The `admin/console` module provides a comprehensive web-based administrative interface for the Vorpal Blade framework. This module offers visualization, configuration management, and monitoring capabilities through an intuitive web console.

## Overview

The Admin Console serves as the primary administrative interface for managing Vorpal Blade applications and services. It features:

- Interactive graph-based visualization of system components
- Web-based configuration management interface  
- Real-time monitoring and analytics dashboards
- RESTful API endpoints with Swagger documentation
- File upload and management capabilities
- Integration with all Vorpal Blade services and modules

## Architecture

This module is built on a modern web architecture combining:

- **Backend**: JAX-RS services with Swagger API documentation
- **Frontend**: JSP-based web application with JSTL support
- **Visualization**: mxGraph integration for interactive diagrams
- **Data Layer**: HSQLDB for configuration persistence
- **Configuration**: Framework v2 configuration system

## Package Structure

### [com.mxgraph.util](#commxgraphutil)
Utility classes extending the mxGraph library functionality for enhanced diagram rendering and manipulation within the console interface.

### [org.vorpal.blade.applications.console.config](#orgvorpalbladeapplicationsconsoleconfig)
Core configuration classes for the admin console application, including settings management, persistence layer configuration, and application-specific parameters.

### [org.vorpal.blade.applications.console.config.test](#orgvorpalbladeapplicationsconsoleconfigtest)
Test utilities and mock configurations for console configuration testing, providing test harnesses and validation tools for configuration components.

### [org.vorpal.blade.applications.console.mxgraph](#orgvorpalbladeapplicationsconsolemxgraph)
Integration layer between the Vorpal Blade framework and mxGraph visualization library, enabling interactive network topology and service relationship diagrams.

### [org.vorpal.blade.applications.console.webapp](#orgvorpalbladeapplicationsconsolewebapp)
Web application layer containing servlets, REST endpoints, JSP pages, and web-specific utilities for the administrative console interface.

### [org.vorpal.blade.framework.v2.config](#orgvorpalbladeframeworkv2config)
Framework v2 configuration system components, providing advanced configuration management, validation, and persistence capabilities.

## Dependencies

### Core Framework
- **org.vorpal.blade:vorpal-blade-library-framework** - Core Vorpal Blade framework library providing foundational services and utilities

### Visualization & Graphics
- **com.github.vlsi.mxgraph:jgraphx** - Interactive graph visualization library for network topology diagrams
- **org.apache.xmlgraphics:fop** - Apache FOP for PDF generation and advanced document formatting

### Database
- **org.hsqldb:hsqldb** - Embedded SQL database for configuration persistence and caching

### Web Framework
- **jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api** - JSTL API for enhanced JSP templating and web page rendering
- **io.swagger.core.v3:swagger-jaxrs2** - Swagger OpenAPI integration for REST API documentation
- **io.swagger.core.v3:swagger-jaxrs2-servlet-initializer-v2** - Swagger servlet initialization and configuration

### Utilities
- **commons-fileupload:commons-fileupload** (1.5) - File upload handling for configuration import/export
- **commons-lang:commons-lang** (2.6) - Common utility functions and string manipulation

## Related Modules

### Core Libraries
- [libs/framework](../libs/framework) - Core framework components
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities
- [libs/fsmar](../libs/fsmar) - File system monitoring and resource management

### Administrative Modules
- [admin/configurator](../admin/configurator) - Configuration management tools

### Service Modules
- [services/acl](../services/acl) - Access Control List service
- [services/analytics](../services/analytics) - Analytics and reporting service
- [services/hold](../services/hold) - Call hold management service
- [services/options](../services/options) - SIP OPTIONS handling service
- [services/presence](../services/presence) - Presence information service
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing proxy service
- [services/proxy-block](../services/proxy-block) - Traffic blocking proxy service
- [services/proxy-registrar](../services/proxy-registrar) - SIP registrar proxy service
- [services/proxy-router](../services/proxy-router) - Routing proxy service
- [services/queue](../services/queue) - Message queue service
- [services/tpcc](../services/tpcc) - Third-party call control service
- [services/transfer](../services/transfer) - Call transfer service

## Integration Guide

### Building the Module

```bash
mvn clean compile
mvn package
```

### Deployment

1. Ensure all dependent modules are built and available
2. Deploy the generated WAR file to your servlet container
3. Configure database connection properties
4. Initialize the admin console through the web interface

### Configuration

The console requires configuration of:

- Database connection parameters for HSQLDB
- Framework v2 configuration file locations  
- Service endpoint URLs for managed modules
- Authentication and authorization settings

### API Access

The console exposes RESTful APIs documented through Swagger UI, accessible at:
- `/api-docs` - OpenAPI specification
- `/swagger-ui` - Interactive API documentation

## Development

### Prerequisites

- Java 8 or higher
- Maven 3.6+
- Servlet container (Tomcat, Jetty, etc.)

### Testing

Run unit tests with:
```bash
mvn test
```

Integration tests require a running servlet container and configured database connection.