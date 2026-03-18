# CLAUDE.md — analytics

SIP-based event analytics collection and storage service. Captures call lifecycle events from SIP sessions and persists them to a MySQL database via JMS message queues.

## Architecture

```
SIP Servlet (AnalyticsSipServlet)
    | captures call events (callStarted, callAnswered, callCompleted, etc.)
    v
JMS Queue (jms/TestJMSQueue)
    | async message processing
    v
Message-Driven Bean (QueueReceive)
    |
    v
MySQL Database via EclipseLink JPA
```

## Key Packages

| Package | Purpose |
|---------|---------|
| `sip/` | SIP servlet handling call lifecycle events |
| `jms/` | Message-driven bean for async event processing |
| `jaxrs/` | REST API endpoints (`/v1/test`) |
| `pojo/` | Data transfer objects (Application, Session, Event, Attribute) |
| `jpa/` | JPA entity classes (generated from database tables) |

## Database

MySQL schema in `sql/MySQL-database-schema.sql`. JPA persistence unit: `BladeCDR`.

## JMS Configuration

Requires WebLogic JMS resources:
- Connection Factory: `jms/TestConnectionFactory`
- Queue: `jms/TestJMSQueue`

## Testing

Integration testing uses SIPP:
```bash
cd test && ./uas.sh   # Start UAS
cd test && ./uac.sh   # Start UAC test scenario
```

## Current State

Much of the JMS/JPA functionality is commented out in `AnalyticsSipServlet.java`. The core infrastructure is in place, but event persistence is still being developed.
