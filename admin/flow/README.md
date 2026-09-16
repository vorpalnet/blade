# Flow — FSMAR Callflow Editor

Javadocs: `/blade/javadoc/flow/` on the Admin Portal

A browser diagram editor for FSMAR application-router configurations, served at
`/blade/flow`. Design SIP callflows visually as state machines — trigger, task, and
transition nodes on an mxGraph canvas — and publish the result to the running cluster.
The diagram round-trips with the FSMAR JSON: import, edit, export, publish.

Flow and the [Configurator](../configurator/README.md) divide the work on `fsmar.json`:
Flow edits the topology (states, transitions, routes) visually; the Configurator's
schema-driven forms cover everything else.

## Opening and saving

The toolbar's Open button lists everything there is to open: the live `fsmar.json`, the
generated sample, and the saved flows under `config/custom/vorpal/_flows/`. Config is
edited on the admin server and pushed to the engines from there, so the domain file is the
live configuration; the rarer per-cluster and per-server overlays stay behind the
Configuration pull-down in the save dialog. A saved flow is an ordinary FSMAR 3 file under
a name of its own (`demo1.json`), kept so a staged routing change, or a demo, can be
reopened later. Nothing in `_flows/` is live: opening one fills the canvas and changes no
call.

Saving is the same list in reverse. Name the file and it lands in the library; name it
`fsmar.json` and it publishes to the selected target instead, through the same diff and
confirmation the Save to fsmar button uses. `FlowFiles` validates every name (letters,
digits, dot, dash and underscore, ending in `.json`), so a save cannot escape the library
directory, and saves go through `VersionedFileStore` like every other config write.

## Publishing

Publish writes `config/custom/vorpal/fsmar.json` in the domain root through the
framework's `VersionedFileStore` — prior content lands in `.versions/` and is restorable
from the Configurator's version history. The FSMAR App Router's own `SettingsManager`
picks the change up on the engine tier; no App Router restart. Flow writes only the
domain-level file; per-cluster and per-server overlays remain the Configurator's job.

## Also in the box

- **FSMAR 2 conversion** — the framework's `Fsmar2Converter` translates legacy FSMAR 2
  configs; anything untranslatable becomes a fail-closed `when: "false"` transition tagged
  with a `REVIEW:` warning rather than silently changing behavior.
- **Validation, diff, and simulation** endpoints, so a config can be checked and dry-run
  before publish.
- **Live metrics and trace capture** from the engine tier over JMX — per-transition hit
  counts, and on-demand capture of routing traces (armed per engine).
- **Round-trip honesty** — unmapped JSON fields ride along in an `extra` attribute and are
  written back untouched; transition order is preserved (first match wins).

In-app documentation lives at `webapp/docs/` — concepts, editor guide, tutorial,
troubleshooting — served with the app itself.

## Related modules

- [libs/fsmar](../../libs/fsmar/README.md) — the App Router runtime this app configures
- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — home of the FSMAR configuration model (`v3.fsmar`)
- [admin/configurator](../configurator/README.md) — the other editor of `fsmar.json`
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-flow</artifactId>
```
