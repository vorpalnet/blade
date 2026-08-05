# iRouter Editor

Author the iRouter's configuration through a purpose-built editor at
`/blade/irouter-editor`, with a live dry-run that shows exactly which Route any
pasted SIP request would get — against the unsaved draft, before anything is
saved or published.

## The editor

Three steps, mirroring the v3 router model: the **pipeline** (connectors and
selectors) gathers facts about each call into variables; the **routing**
strategy (`table` / `conditional` / `direct`) turns them into a Route; the
**dry-run** parses a pasted request, runs the draft pipeline inline, asks the
draft routing to decide (`RoutePreviewEngine` in the framework — the same
`Routing.decide` production runs), and states the result in words: the resolved
forward URI, the final response, the stamped headers with skipped conditional
headers listed, and every variable the pipeline extracted.

All forms are generated from the service's JSON Schema
(`_schemas/irouter.jschema`) by the shared editor kit
(`/blade/portal/lib/blade-editor.js`), so new connector, selector,
authentication, or routing types appear without editor changes. Save validates
the document as the iRouter would load it, encrypts `{CLEARTEXT}` credentials,
and writes `irouter.json` through the framework's `VersionedFileStore`
(restorable history in the Versions panel); Publish reloads the running
iRouter nodes via `ConfigPublisher`. Logging, session settings, and
cluster/server overlays stay in the Configurator, which the page links to —
the Flow-vs-Configurator division of labour.

## Roles

Admin and Operator only — it exists to change routing behavior, so the
read-mostly roles stay out.

## Related modules

- [services/irouter](../../services/irouter/README.md) — the engine-tier service this configures
- [admin/crud-editor](../crud-editor/README.md) — the sibling editor sharing the same kit
- [admin/portal](../portal/README.md) — hosts the shared editor kit and brand assets
- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — the router model and `RoutePreviewEngine`

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-irouter-editor</artifactId>
```
