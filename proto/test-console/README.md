# BLADE Test Console

[Javadocs](https://vorpal.net/javadocs/blade/test-console)

Cluster-wide control surface for the BLADE test apps
([test-uac](../../test/test-uac), [test-uas](../../test/test-uas)): start and
stop load runs on every node at once and watch live per-scenario metrics —
call counts, status distributions, latency percentiles, assertion pass/fail —
aggregated over federated JMX. The admin-tier face of BLADE's SIPp
replacement.

## How it works

- Every tester node registers a `vorpal.blade:Name=<app>,Type=TesterControl`
  MBean (framework `TesterControl` / `TesterMXBean`) exposing
  start/stop/status/report as JSON.
- `ConsoleAPI` (JAX-RS at `/api`) discovers those MBeans through the
  federated DomainRuntime MBeanServer — the admin tier never uses REST
  inward — and fans commands out to every matching node.
- `console.html` + `console.js` poll `/api/cluster` and render per-node
  status cards plus an aggregated per-scenario table (counters summed across
  nodes; latency columns show the worst node, since percentiles don't merge).

## REST facade (browser ↔ console only)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/cluster?app=` | Every tester node with live status + metrics report |
| `POST` | `/api/start?app=&server=` | Start a load run (body: LoadRequest JSON) on matching nodes |
| `POST` | `/api/stop?app=&server=` | Stop; active calls drain |
| `POST` | `/api/reset?app=&server=` | Clear per-scenario metrics |

Run targets are **per node** — 3 nodes × 100 CPS = 300 CPS aggregate.

## Scenarios

Scenarios themselves (response scripts, transformation rule sets, templates,
assertions) live in `test-uac.json` / `test-uas.json` and are edited
schema-validated in the [Configurator](../configurator). The console runs
them; it doesn't define them.

## Deployment

Part of `blade-admin.ear` (AdminServer):

- **Context root**: `blade/test-console`
- **Auth**: FORM, shared `BLADEADMINSESSION` cookie (portal SSO)
- **Brand**: chromed by `/blade/portal/brand/brand.css`
- Appears on the Admin Portal launcher deck via its SettingsManager `about`
  metadata

## License

Part of the Vorpal Blade SIP Servlet Container project.
