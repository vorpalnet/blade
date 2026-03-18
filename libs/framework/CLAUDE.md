# CLAUDE.md — framework

Core SIP Servlet framework library extending JSR-359 with asynchronous callflow-based patterns using lambda expressions.

## Class Hierarchy

```
SipServlet (JSR-359)
  +-- AsyncSipServlet (v2)
        +-- B2buaServlet - Back-to-back user agent applications
        +-- ProxyServlet - Proxy-style applications
```

## Key Packages

- **v2** - Core framework classes
- **v2.callflow** - Callflow base classes and utilities
- **v2.b2bua** - B2BUA callflow implementations (InitialInvite, Reinvite, Bye, Cancel, Passthru)
- **v2.proxy** - Proxy callflow implementations
- **v2.config** - JSON configuration and SettingsManager
- **v2.logging** - SIP-aware logging utilities
- **v2.transfer** - Call transfer implementations (BlindTransfer, AttendedTransfer, ReferTransfer)

## Callflow Pattern

```java
sendRequest(bobRequest, (bobResponse) -> {
    SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
    sendResponse(aliceResponse, (aliceAck) -> {
        SipServletRequest bobAck = bobResponse.createAck();
        sendRequest(bobAck);
    });
});
```

## B2BUA Lifecycle Methods

Override these in `B2buaServlet` subclasses:
- `callStarted(request)` - Outbound INVITE to Bob
- `callAnswered(response)` - Final response to Alice
- `callConnected(request)` - ACK sent to Alice
- `callCompleted(request)` - BYE request
- `callDeclined(response)` - Error code from Bob
- `callAbandoned(request)` - CANCEL from Alice

## Configuration System

Uses `SettingsManager<T>` with JSON config files in `./config/custom/vorpal/<app-name>.json`. Supports hierarchical merging (server > cluster > domain) and live updates via JMX through the blade admin console.

## Bundled Libraries

- **javax.sdp** - SDP (Session Description Protocol) interfaces
- **gov.nist.javax.sdp** - NIST SDP implementation
- **gov.nist.core** - NIST core utilities
