# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is **vorpal-blade-services-analytics**, a SIP-based event analytics collection and storage service for telecommunications applications. It captures call lifecycle events from SIP sessions and persists them to a MySQL database via JMS message queues.

## Build System

This is an Eclipse IDE project (no Maven/Gradle). Build configuration:
- **Java Version:** 11 (OpenJDK 11)
- **Build Output:** `build/classes/`
- **Target Server:** Oracle WebLogic Server 14.1.1.0

To build, use Eclipse IDE: Project > Build Project

## Framework Dependency

This service depends on `vorpal-blade-library-framework` v2.0, which must be deployed as a shared library to WebLogic. Key framework classes used:
- `B2buaServlet` - Base SIP servlet with call lifecycle hooks
- `SettingsManager` - Configuration management
- `sipLogger` - Logging utilities

## Architecture

```
SIP Servlet (AnalyticsSipServlet)
    │ captures call events (callStarted, callAnswered, callCompleted, etc.)
    ▼
JMS Queue (jms/TestJMSQueue)
    │ async message processing
    ▼
Message-Driven Bean (QueueReceive)
    │
    ▼
MySQL Database via EclipseLink JPA
```

### Key Components

| Package | Purpose |
|---------|---------|
| `sip/` | SIP servlet handling call lifecycle events |
| `jms/` | Message-driven bean for async event processing |
| `jaxrs/` | REST API endpoints (`/v1/test`) |
| `pojo/` | Data transfer objects (Application, Session, Event, Attribute) |
| `jpa/` | JPA entity classes (generated from database tables) |

### Main Entry Point
`AnalyticsSipServlet.java` - Extends `B2buaServlet`, implements `B2buaListener` for call events.

## Database

MySQL database with schema in `sql/MySQL-database-schema.sql`. Tables:
- `application` - Tracks deployment instances
- `session` - SIP sessions
- `session_key` - Session lookup keys (selectors)
- `event` - Call lifecycle events (callStarted, callAnswered, etc.)
- `attribute` - Event key-value metadata (From, To headers)

JPA persistence unit: `BladeCDR` (configured in `src/main/java/META-INF/persistence.xml`)

## Configuration

| File | Purpose |
|------|---------|
| `src/main/webapp/WEB-INF/web.xml` | Servlet 4.0 deployment descriptor |
| `src/main/webapp/WEB-INF/weblogic.xml` | WebLogic config, context root: `/analytics` |
| `src/main/java/META-INF/persistence.xml` | EclipseLink JPA configuration |

## JMS Configuration

Requires WebLogic JMS resources:
- Connection Factory: `jms/TestConnectionFactory`
- Queue: `jms/TestJMSQueue`

## Testing

Integration testing uses SIPP (SIP protocol testing tool):

```bash
# Start UAS (User Agent Server)
cd test && ./uas.sh

# Start UAC (User Agent Client) - runs test scenario
cd test && ./uac.sh
```

Test configuration in `test/uac.xml` and `test/uas.xml`.

## Current Development State

Much of the JMS/JPA functionality is commented out in `AnalyticsSipServlet.java`. The core infrastructure (SIP event capture, JMS queue setup) is in place, but event persistence is still being developed.
