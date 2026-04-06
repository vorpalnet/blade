# Test User Agent Server (UAS)

[Javadocs](https://vorpal.net/javadocs/blade/test-uas)

A SIP test server built on the BLADE framework that answers incoming calls with configurable response behavior. The Test UAS is the counterpart to the [Test UAC](../test-uac) — together they form a complete SIP load testing tool that replaces SIPp for production call center performance tuning.

## Overview

The Test UAS accepts inbound SIP calls and responds based on a flexible, layered configuration model. Response behavior — including status codes, delays, call duration, and SDP content — can be controlled three ways:

1. **Configuration file** — JSON-based defaults loaded at startup via SettingsManager
2. **REST API** — modify behavior at runtime without redeployment
3. **Request URI parameters** — per-call overrides embedded in the SIP request itself

This layered approach lets the [Test UAC](../test-uac) (or SIPp, or any SIP client) control UAS behavior from the caller side by encoding parameters in the request URI, while the REST API provides a control plane for test orchestration tools.

## Features

- Configurable response status codes (200, 404, 503, or any valid SIP status)
- Configurable response delay for simulating network or processing latency
- Automatic BYE after configurable call duration for controlled call teardown
- Error map for phone-number-to-error-code routing (e.g. dial 18165550404 to get a 404)
- Customizable SDP content for media negotiation testing
- REST API for runtime configuration changes — no redeployment needed
- Request URI parameter overrides: `?status=503&delay=5s&duration=60s`
- REFER-based call transfer testing with NOTIFY handshaking
- Re-INVITE handling with blackhole SDP (media hold simulation)
- Full OpenAPI/Swagger documentation
- Hot-reloadable configuration via SettingsManager and JMX MBeans

## Response Behavior

### Configuration Precedence

Response parameters are resolved in this order (highest priority first):

1. **Error map** — if the called phone number matches an entry, that status code wins
2. **Request URI parameters** — `?status=`, `?delay=`, `?duration=` override defaults
3. **REST API configuration** — runtime defaults set via `PUT /api/v1/config/*`
4. **Configuration file** — startup defaults from `config/custom/vorpal/test-uas.json`

### Example: URI Parameter Overrides

The UAC (or any SIP client) can control UAS behavior per-call:

```
sip:target@uas.test;status=503;delay=2s;duration=0
```

This tells the UAS to wait 2 seconds, then respond with 503 Service Unavailable, with no auto-BYE (duration=0 means the UAS won't initiate teardown).

### Example: Error Map

Configure specific phone numbers to return specific errors:

```json
{
  "errorMap": {
    "18165550404": 404,
    "18165550503": 503,
    "18165550607": 607,
    "18005551234": 486
  }
}
```

When a call arrives for `sip:18165550404@uas.test`, the UAS responds with 404 Not Found regardless of other settings.

## REST API

### Configuration Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/config` | Get current configuration |
| `PUT` | `/api/v1/config` | Replace entire configuration |
| `PUT` | `/api/v1/config/status` | Update default response status code |
| `PUT` | `/api/v1/config/delay` | Update default response delay |
| `PUT` | `/api/v1/config/duration` | Update default call duration |
| `PUT` | `/api/v1/config/errormap` | Replace error map |

### Examples

Set all calls to respond with 503 after a 1-second delay:

```bash
curl -X PUT http://engine:8001/test-uas/api/v1/config/status \
  -H "Content-Type: application/json" \
  -d '{"defaultStatus": 503}'

curl -X PUT http://engine:8001/test-uas/api/v1/config/delay \
  -H "Content-Type: application/json" \
  -d '{"defaultDelay": "1s"}'
```

Set call duration to 2 minutes before auto-BYE:

```bash
curl -X PUT http://engine:8001/test-uas/api/v1/config/duration \
  -H "Content-Type: application/json" \
  -d '{"defaultDuration": "2m"}'
```

View current configuration:

```bash
curl http://engine:8001/test-uas/api/v1/config
```

### API Documentation

- **OpenAPI**: `http://<engine:port>/test-uas/resources/openapi.json`
- **WADL**: `http://<engine:port>/test-uas/resources/application.wadl?detail=true`

## Configuration

Configuration is managed by [SettingsManager](https://vorpal.net/javadocs/blade/framework) and stored at `config/custom/vorpal/test-uas.json`. Changes can be made via the Configurator GUI, REST API, direct file editing, or JMX MBeans.

### Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `defaultStatus` | `200` | SIP response status code |
| `defaultDelay` | `0s` | Delay before sending response (supports ms/s/m/h) |
| `defaultDuration` | `30s` | Call duration before auto-BYE (supports s/m/h/d) |
| `sdpContent` | *(built-in)* | SDP body for 2xx responses, or null for the built-in Qfiniti SIPREC SDP |
| `errorMap` | *(see below)* | Phone number to SIP error code mappings |

### Default Error Map

```json
{
  "18165550404": 404,
  "18165550503": 503,
  "18165550607": 607
}
```

### Duration Format

All duration parameters accept human-readable strings:

| Suffix | Unit | Example |
|--------|------|---------|
| `s` | seconds | `30s` |
| `m` | minutes | `5m` |
| `h` | hours | `1h` |
| `d` | days | `1d` |
| *(none)* | seconds | `30` |

## Callflow Architecture

The UAS dispatches incoming SIP requests to specialized callflows:

| SIP Method | Callflow | Behavior |
|------------|----------|----------|
| INVITE (initial) | **TestInvite** | Responds with configured status, delay, SDP, and auto-BYE |
| INVITE (mid-dialog) | **TestReinvite** | Responds with blackhole SDP (media hold simulation) |
| BYE, CANCEL, INFO | **TestOkayResponse** | Responds with 200 OK |
| REFER | **TestRefer** | REFER-based transfer with NOTIFY handshaking |
| *(other)* | **TestNotImplemented** | Responds with 501 Not Implemented |

### TestInvite Detail

The primary callflow for load testing. On each incoming INVITE:

1. Reads config defaults from SettingsManager
2. Checks for request URI parameter overrides (`?status=`, `?delay=`, `?duration=`)
3. Checks the error map for phone number matches
4. If delay > 0, schedules a timer before responding
5. Sends the response with configured status code and SDP (for 2xx responses)
6. If 2xx and duration > 0, schedules an auto-BYE timer to tear down the call

### TestRefer Detail

Tests SIP REFER-based call transfer:

1. Answers the initial INVITE with 200 OK
2. Sends REFER with `Refer-To` address from the URI `refer` parameter
3. Expects NOTIFY with "100 Trying" (implicit subscription)
4. Expects second NOTIFY with transfer outcome
5. On success, sends BYE to tear down the original dialog

## Package Structure

### [`org.vorpal.blade.test.uas`](#orgvorpalbladetestuas)

Core UAS module:
- **UasServlet** — main SIP servlet extending B2buaServlet, dispatches requests to callflows based on SIP method

### [`org.vorpal.blade.test.uas.callflows`](#orgvorpalbladetestuascallflows)

SIP callflow implementations:
- **TestInvite** — initial INVITE with configurable delay, status, duration, error map, and SDP
- **TestReinvite** — mid-dialog re-INVITE with blackhole SDP
- **TestOkayResponse** — simple 200 OK for BYE, CANCEL, INFO
- **TestNotImplemented** — 501 for unsupported methods
- **TestRefer** — REFER-based transfer with NOTIFY handshaking
- **UasCallflow** — generic response with configurable status code

### [`org.vorpal.blade.test.uas.config`](#orgvorpalbladetestuasconfig)

Configuration classes:
- **TestUasConfig** — response defaults, error map, SDP content, with computed duration getters
- **TestUasConfigSample** — sample configuration with default values
- **TestUasState** — state descriptor with delay parsing (ms/s/m/h)
- **TestUasHeaders** — SIP header name/value pair model

### [`org.vorpal.blade.test.uas.api`](#orgvorpalbladetestuasapi)

REST API:
- **TestUasAPI** — JAX-RS endpoints for runtime configuration management

## Dependencies

### Core Dependencies

- **`org.vorpal.blade:vorpal-blade-library-framework`** — BLADE framework providing B2buaServlet, Callflow, SettingsManager, and SIP container integration

## Related Modules

### Core Framework
- [**libs/framework**](../../libs/framework) — Base framework with B2buaServlet, Callflow, and SettingsManager
- [**libs/shared**](../../libs/shared) — Third-party libraries (Jackson, Swagger, SLF4J)

### Test Modules
- [**test/test-uac**](../test-uac) — Test User Agent Client (call generator) — the other half of load testing
- [**test/test-b2bua**](../test-b2bua) — Test Back-to-Back User Agent

### Services
- [**services/analytics**](../../services/analytics) — Call analytics and event publishing for test metrics

## Deployment

The Test UAS deploys as a WAR to Oracle OCCAS with the `vorpal-blade` shared library:

- **Context root**: `test-uas`
- **WebLogic shared library**: `vorpal-blade` (specification version 2.0)

For cluster-wide load testing, deploy to every engine tier node alongside the [Test UAC](../test-uac). Each UAS instance independently handles its share of incoming calls.

## License

Part of the Vorpal Blade SIP Servlet Container project.
