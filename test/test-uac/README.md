# Test User Agent Client (UAC)

[Javadocs](https://vorpal.net/javadocs/blade/test-uac)

A scenario-driven SIP load generator and test client built on the BLADE
framework. The Test UAC originates SIP calls at scale — replacing SIPp with a
reliable, API-driven, cluster-native alternative — and, as a B2BUA, can also
take *real* calls from a softphone or SBC and transform them en route (e.g.
dressing a plain call up as a SIPREC recorder leg). It is the counterpart to
the [Test UAS](../test-uas).

## One tool, two call sources

Both sides share the same machinery — a **scenario** describes what happens to
a call; where the call comes from is orthogonal:

| Source | Role | What happens |
|---|---|---|
| Synthesized by the load engine | `originate` | INVITEs generated at a target CPS or concurrency, scripted by a scenario: template body, transformation rules, expected responses, assertions |
| Real call from a softphone/SBC | `b2bua` | The call passes through; the scenario's template and rules transform it (insert/delete headers and body parts) before forwarding |

Scenarios live in `test-uac.json` — edited schema-validated in the BLADE
**Configurator** — and share the CRUD engine's `ruleSets` for message
transformation (headers, MIME parts, SDP/XML/JSON path edits, `${variable}`
capture and substitution).

## Scenarios

```jsonc
{
  "originate": {
    "scenario": "load-basic",
    "fromAddressPattern": "sip:load-${index}@blade.test",
    "toAddressPattern": "sip:target@uas.test",
    "requestUriTemplate": "sip:target@uas.test",
    "duration": "30s",
    "load": { "mode": "cps", "targetCps": 10 }
  },
  "scenarios": {
    "load-basic": {
      "role": "originate",
      "responseScript": { "expectFinal": "2xx" },
      "assertions": [
        { "id": "answered",   "when": "${lastStatus} >= 200 && ${lastStatus} < 300" },
        { "id": "fast-setup", "when": "${setupMs} < 500", "onFail": "warn" }
      ]
    },
    "siprec-originate": {
      "role": "originate",
      "template": "invite-template-siprec.txt",
      "responseScript": { "expectFinal": "2xx" }
    },
    "siprec-b2bua": {
      "role": "b2bua",
      "template": "invite-template-siprec.txt"
    }
  }
}
```

- **Templates** (`config/custom/vorpal/_templates/`) are raw SIP message
  fragments — request line, headers, blank line, body. A multipart template
  merges with the live message: the template's SDP wins (SIPREC labels must
  match the metadata); without one, the softphone's SDP is preserved and the
  template's other parts (e.g. `application/rs-metadata+xml`) are appended.
  Templates hot-reload on file change.
- **Rule sets** are the CRUD engine's — `create`/`read`/`update`/`delete`
  operations plus `keepPart`, filtered by method, request/response, lifecycle
  event, and status range. A scenario can reference a shared `ruleSets` entry
  and/or carry inline `rules`.
- **Assertions** are boolean expressions over per-call variables —
  `${lastStatus}`, `${statusSequence}`, `${setupMs}`, plus anything a rule's
  `read` operation captured — tallied per scenario as pass/fail/warn.

## Load Generation

### CPS mode

Fires calls at a fixed rate. Above 1000 CPS, calls are batched across timer
ticks to maintain precision.

```bash
curl -X POST http://engine:8001/test-uac/api/v1/loadtest/start \
  -H "Content-Type: application/json" \
  -d '{ "scenario": "siprec-originate", "mode": "cps", "targetCps": 500 }'
```

### Concurrent mode

Maintains a fixed number of active calls; completions trigger replacements.

```bash
curl -X POST http://engine:8001/test-uac/api/v1/loadtest/start \
  -H "Content-Type: application/json" \
  -d '{ "scenario": "load-basic", "mode": "concurrent", "targetConcurrent": 200 }'
```

Null request fields fall back to the configuration's `originate` defaults.

### Monitoring and reports

```bash
curl http://engine:8001/test-uac/api/v1/loadtest/status
curl http://engine:8001/test-uac/api/v1/loadtest/report
```

The report carries per-scenario counters, a final-status distribution,
latency percentiles (p50/p90/p99, avg, max), expectation mismatches, and
assertion tallies:

```json
[ {
  "scenario": "siprec-originate",
  "started": 15042, "completed": 14680, "failed": 50,
  "finalStatusCounts": { "200": 14930, "486": 62 },
  "latencyCount": 14992, "latencyAvgMs": 38, "latencyMaxMs": 612,
  "latencyP50Ms": 25, "latencyP90Ms": 50, "latencyP99Ms": 250,
  "assertionsPassed": 14930, "assertionsFailed": 62, "expectMismatched": 62
} ]
```

### Stopping

```bash
curl -X POST http://engine:8001/test-uac/api/v1/loadtest/stop
```

Active calls drain naturally.

## REST API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/loadtest/start` | Start load generation on this node (optional `scenario`) |
| `POST` | `/api/v1/loadtest/stop` | Stop load generation on this node |
| `GET` | `/api/v1/loadtest/status` | Current load status |
| `GET` | `/api/v1/loadtest/report` | Per-scenario metrics report |
| `POST` | `/api/v1/loadtest/reset` | Clear the metrics |
| `POST` | `/api/v1/connect` | Send a single INVITE (scenario/template/inline body) and collect every response |
| `POST` | `/api/v1/tpcc` | Initiate a third-party call control session |

- **OpenAPI**: `http://<engine:port>/test-uac/resources/openapi.json`

## Test Console & JMX

Every node also registers a `TesterControl` MBean
(`vorpal.blade:Name=test-uac,Type=TesterControl,Cluster=…`) exposing
start/stop/status/report. The **BLADE Test Console** admin app
(`blade/test-console`) drives whole-cluster runs and aggregates every node's
report over federated JMX — no per-node curl needed.

## Configuration

Managed by [SettingsManager](https://vorpal.net/javadocs/blade/framework) at
`config/custom/vorpal/test-uac.json`; edit in the Configurator, by file, or
via JMX. The generated `test-uac.json.SAMPLE` contains worked scenarios.

Backward compatibility: the pre-scenario top-level fields
(`fromAddressPattern`, `toAddressPattern`, `requestUriTemplate`, `duration`)
still load — they feed the `originate` block — and a top-level `template`
still applies to outbound softphone INVITEs when the resolved scenario has no
template of its own.

## Package Structure

### `org.vorpal.blade.test.uac`
- **UserAgentClientServlet** — thin leaf over the framework's `TesterServlet`
- **UserAgentClientConfig** / **UserAgentClientConfigSample** — concrete `TesterConfiguration`
- **LoadTestAPI** — REST endpoints (start/stop/status/report/reset)

### `org.vorpal.blade.framework.v3.tester` *(framework)*
- **LoadEngine** — per-node CPS/concurrent pacing
- **OriginateCallflow** — INVITE → rules → ACK → auto-BYE, expectations + assertions + metrics
- **Scenario / ResponseScript / Assertion** — the scenario model
- **SipMessageTemplate / TemplateLoader** — template parsing/merging, mtime hot-reload
- **TesterMetrics / ScenarioReport** — per-scenario counters and latency buckets
- **TesterControl / TesterMXBean** — admin-tier JMX control surface

### `org.vorpal.blade.test.client`
- **TestClientAPI** — `POST /api/v1/connect`, one HTTP request → one INVITE → all responses

### `org.vorpal.blade.framework.tpcc`
- **ThirdPartyCallControl** — `POST /api/v1/tpcc` for external call setup

## Related Modules

- [**test/test-uas**](../test-uas) — Test User Agent Server — the other half of the pair
- [**libs/framework**](../../libs/framework) — BLADE framework (tester + CRUD engines)
- [**proto/test-console**](../../proto/test-console) — cluster-wide run control and live dashboards

## Deployment

Deploys as a WAR to Oracle OCCAS with the `blade-shared` shared library:

- **Context root**: `test-uac`
- **WebLogic shared library**: `blade-shared` (specification version 3.0)

Deploy to every engine-tier node; drive runs per node via REST or
cluster-wide from the Test Console.

## License

Part of the Vorpal Blade SIP Servlet Container project.
