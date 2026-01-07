# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Vorpal:BLADE is a SIP Servlet framework library that simplifies application development on the Oracle Communications Converged Application Server (OCCAS). It extends JSR-359 SIP Servlet APIs with asynchronous callflow-based patterns using lambda expressions.

## Build Commands

This project uses Apache Ant for building:

```bash
# Build the project (compile and create JAR)
ant build

# Compile only
ant compile
```

The build requires parent project files (`../build.properties`, `../libraries.xml`) and OCCAS-specific libraries configured in the classpath.

## Architecture

### Core Class Hierarchy

```
SipServlet (JSR-359)
  └── AsyncSipServlet (v2)
        ├── B2buaServlet - Back-to-back user agent applications
        └── ProxyServlet - Proxy-style applications
```

### Key Packages

- **org.vorpal.blade.framework.v2** - Core framework classes
- **org.vorpal.blade.framework.v2.callflow** - Callflow base classes and utilities
- **org.vorpal.blade.framework.v2.b2bua** - B2BUA callflow implementations (InitialInvite, Reinvite, Bye, Cancel, Passthru)
- **org.vorpal.blade.framework.v2.proxy** - Proxy callflow implementations
- **org.vorpal.blade.framework.v2.config** - JSON configuration and SettingsManager
- **org.vorpal.blade.framework.v2.logging** - SIP-aware logging utilities
- **org.vorpal.blade.framework.v2.transfer** - Call transfer implementations (BlindTransfer, AttendedTransfer, ReferTransfer)
- **org.vorpal.blade.framework.v2.keepalive** - Session keep-alive handling

### Callflow Pattern

The framework uses lambda expressions for asynchronous SIP message handling:

```java
sendRequest(bobRequest, (bobResponse) -> {
    SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
    sendResponse(aliceResponse, (aliceAck) -> {
        SipServletRequest bobAck = bobResponse.createAck();
        sendRequest(bobAck);
    });
});
```

Key methods on `Callflow`:
- `sendRequest(request, responseCallback)` - Send request, handle response via lambda
- `sendResponse(response, ackCallback)` - Send response, handle ACK/PRACK via lambda
- `expectRequest(session, method, callback)` - Register callback for expected requests (e.g., CANCEL)
- `linkSessions(session1, session2)` - Link two SIP sessions together
- `getLinkedSession(session)` - Get the linked session

### B2BUA Lifecycle Methods

When extending `B2buaServlet`, override these methods to intercept outgoing messages:
- `callStarted(request)` - Outbound INVITE to Bob
- `callAnswered(response)` - Final response to Alice
- `callConnected(request)` - ACK sent to Alice
- `callCompleted(request)` - BYE request
- `callDeclined(response)` - Error code from Bob
- `callAbandoned(request)` - CANCEL from Alice

### Configuration System

Uses `SettingsManager<T>` with JSON configuration files:
- Config files stored in `./config/custom/vorpal/<app-name>.json`
- Supports hierarchical merging: server > cluster > domain
- Live updates via JMX when using `vorpal-blade-console.war`

## Bundled Libraries

The project includes:
- **javax.sdp** - SDP (Session Description Protocol) interfaces
- **gov.nist.javax.sdp** - NIST SDP implementation
- **gov.nist.core** - NIST core utilities

## Deployment

Deploy `vorpal-blade-shared-libraries-2.x.x.war` to the cluster, then reference in applications via `weblogic.xml`:
```xml
<wls:library-ref>
    <wls:library-name>vorpal-blade</wls:library-name>
    <wls:specification-version>2.0</wls:specification-version>
</wls:library-ref>
```
