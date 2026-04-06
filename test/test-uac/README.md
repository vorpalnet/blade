# Test User Agent Client (UAC)

[Javadocs](https://vorpal.net/javadocs/blade/test-uac)

A SIP load generation tool and test client built on the BLADE framework. The Test UAC generates outbound SIP calls at scale, replacing tools like SIPp with a more reliable, API-driven alternative designed for production call center performance tuning.

## Overview

The Test UAC serves two purposes:

1. **Load Generation** — fire thousands of calls per second across an OCCAS cluster, with each node independently generating calls at a target CPS rate or maintaining a target number of concurrent calls
2. **Single-Call Testing** — send individual SIP requests via REST API for functional testing, third-party call control (TPCC), and SIP message inspection

Each node in the cluster receives commands independently via REST and manages its own local timer and counters. There is no centralized coordinator — the SIP container's threading model handles all call lifecycle concurrency.

## Features

- Two load generation modes: **CPS** (calls per second) and **concurrent** (maintain N active calls)
- High-performance design targeting **1000+ CPS per node** in a cluster
- Per-call `${index}` variable substitution for unique From/To addresses
- Configurable call duration with automatic BYE after timeout
- Custom SIP header injection on all generated calls
- REST API for start, stop, and real-time status monitoring
- Single-call REST API for functional testing and debugging
- Third-Party Call Control (TPCC) REST endpoint
- Full OpenAPI/Swagger documentation
- Hot-reloadable configuration via SettingsManager and JMX MBeans

## Load Generation

### CPS Mode

Fires calls at a fixed rate. At high CPS (above 1000), calls are batched across timer ticks to maintain precision.

```bash
curl -X POST http://engine:8001/test-uac/api/v1/loadtest/start \
  -H "Content-Type: application/json" \
  -d '{
    "mode": "cps",
    "targetCps": 500,
    "duration": "30s",
    "fromAddressPattern": "sip:load-${index}@blade.test",
    "toAddressPattern": "sip:target@uas.test",
    "requestUriTemplate": "sip:target@uas.test;duration=30s"
  }'
```

### Concurrent Mode

Maintains a fixed number of active calls. As each call completes (BYE or failure), a new call is immediately generated to replace it. No timer is needed — replenishment is driven by SIP completion callbacks.

```bash
curl -X POST http://engine:8001/test-uac/api/v1/loadtest/start \
  -H "Content-Type: application/json" \
  -d '{
    "mode": "concurrent",
    "targetConcurrent": 200,
    "duration": "60s",
    "fromAddressPattern": "sip:load-${index}@blade.test",
    "toAddressPattern": "sip:target@uas.test"
  }'
```

### Monitoring

```bash
curl http://engine:8001/test-uac/api/v1/loadtest/status
```

Returns:

```json
{
  "state": "RUNNING",
  "mode": "cps",
  "targetCps": 500.0,
  "activeCalls": 312,
  "totalStarted": 15042,
  "totalCompleted": 14680,
  "totalFailed": 50,
  "elapsedMilliseconds": 30120
}
```

### Stopping

```bash
curl -X POST http://engine:8001/test-uac/api/v1/loadtest/stop
```

Active calls drain naturally — the generator stops creating new calls, and existing calls complete their BYE timers.

## REST API

### Load Generator

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/loadtest/start` | Start load generation on this node |
| `POST` | `/api/v1/loadtest/stop` | Stop load generation on this node |
| `GET` | `/api/v1/loadtest/status` | Get current load test status |

### Single-Call Testing

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/connect` | Send a single SIP INVITE with custom headers and content |
| `POST` | `/api/v1/tpcc` | Initiate a third-party call control session |

### API Documentation

- **OpenAPI**: `http://<engine:port>/test-uac/resources/openapi.json`
- **WADL**: `http://<engine:port>/test-uac/resources/application.wadl?detail=true`

## Configuration

Configuration is managed by [SettingsManager](https://vorpal.net/javadocs/blade/framework) and stored at `config/custom/vorpal/test-uac.json`. Changes can be made via the Configurator GUI, direct file editing, or JMX MBeans.

### Load Test Defaults

| Parameter | Default | Description |
|-----------|---------|-------------|
| `fromAddressPattern` | `sip:load-${index}@blade.test` | From address template with `${index}` substitution |
| `toAddressPattern` | `sip:target@uas.test` | To address template |
| `requestUriTemplate` | `sip:target@uas.test` | Request URI template |
| `duration` | `30s` | Call hold time before auto-BYE (supports s/m/h/d) |
| `sdpContent` | *(none)* | SDP body for INVITE, or null for no SDP |

### SIP Headers

Custom headers are injected into every outbound INVITE (both load-generated and single-call):

| Header | Default | Description |
|--------|---------|-------------|
| `Min-SE` | `90` | Minimum session expiration |
| `Session-Expires` | `2400;refresher=uac` | Session timer with UAC refresh |
| `Supported` | `timer` | Advertise session timer support |
| `X-Genesys-CallUUID` | `123potatoXYZ` | Vendor-specific call correlation |

### Configuration Precedence

For load tests, the REST request body overrides configuration file defaults. Null or empty fields in the request fall back to config values. Headers are merged — request headers override config headers with the same name.

## Package Structure

### [`org.vorpal.blade.test.uac`](#orgvorpalbladetestuac)

Core UAC module:
- **UserAgentClientServlet** — main SIP servlet extending B2buaServlet, injects configured headers, notifies load generator on call lifecycle events
- **UserAgentClientConfig** — configuration with header map, address patterns, duration, and SDP content
- **LoadGenerator** — per-node load generation engine with Java Timer-based CPS pacing and callback-driven concurrent mode
- **LoadCallflow** — per-call lifecycle handler (INVITE -> ACK -> auto-BYE timer -> completion notification)
- **LoadTestAPI** — JAX-RS REST endpoints for start/stop/status
- **LoadTestRequest** / **LoadTestStatus** — request and response models

### [`org.vorpal.blade.test.client`](#orgvorpalbladetestclient)

Single-call REST API:
- **TestClientAPI** — JAX-RS endpoint (`POST /api/v1/connect`) bridging HTTP to SIP INVITE with async response handling
- **MessageRequest** / **MessageResponse** / **MessageSession** — REST-to-SIP data models

### [`org.vorpal.blade.framework.tpcc`](#orgvorpalbladeframeworktpcc)

Third-Party Call Control:
- **ThirdPartyCallControl** — JAX-RS endpoint (`POST /api/v1/tpcc`) for external call setup
- **Simple** — classic TPCC pattern with dual INVITE and blackhole SDP

## Dependencies

### Core Dependencies

- **`org.vorpal.blade:vorpal-blade-library-framework`** — BLADE framework providing B2buaServlet, Callflow, SettingsManager, and SIP container integration

## Related Modules

### Core Framework
- [**libs/framework**](../../libs/framework) — Base framework with B2buaServlet, Callflow, and SettingsManager
- [**libs/shared**](../../libs/shared) — Third-party libraries (Jackson, Swagger, SLF4J)

### Test Modules
- [**test/test-uas**](../test-uas) — Test User Agent Server (call responder) — the other half of load testing
- [**test/test-b2bua**](../test-b2bua) — Test Back-to-Back User Agent

### Services
- [**services/analytics**](../../services/analytics) — Call analytics and event publishing for test metrics

## Deployment

The Test UAC deploys as a WAR to Oracle OCCAS with the `vorpal-blade` shared library:

- **Context root**: `test-uac`
- **WebLogic shared library**: `vorpal-blade` (specification version 2.0)

For cluster-wide load testing, deploy to every engine tier node and send start commands to each node independently.

## License

Part of the Vorpal Blade SIP Servlet Container project.
