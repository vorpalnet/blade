# API Explorer

Javadocs: `/blade/javadoc/api/` on the Admin Portal

Discovers every deployed BLADE service that publishes an OpenAPI document and renders it
as an interactive, try-it-out API reference at `/blade/api`. Pick a service from the
pulldown, or deep-link straight to one with `?app=<service>`.

## How discovery works

The app walks the domain's runtime MBean tree for every active web application — all
context-roots, not just `blade/*`, because services deploy at flat roots like `/hold` —
then concurrently probes each at `<engineBaseUrl>/<contextRoot>/resources/openapi.json`.
Only apps that answer 200 enter the pulldown. Specs are fetched through a constrained
same-origin proxy that can only resolve within the configured engine base URL, and
rendered with the bundled Scalar viewer.

## Configuration

`./config/custom/vorpal/blade-api.json` (named for the `blade-api.war` deployment,
like every admin-tier config file) has one real field:

| Setting | Description |
| --- | --- |
| `engineBaseUrl` | The externally visible engine-tier address (e.g. `https://engines.example_co.net:8002`). The browser makes live "try it" requests here, so it must be reachable from the operator's machine, not just from the AdminServer. |

Edit and publish through the [Configurator](../configurator/README.md).

## Related modules

- [admin/portal](../portal/README.md) — hosts the launcher card
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-api</artifactId>
```
