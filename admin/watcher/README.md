# BLADE Watcher (Legacy Compatibility Shim)

> ⚠️ **DEPRECATED — transitional artifact.** This module exists only to
> preserve the auto-publish-on-file-change behavior of the old
> `console`/`blade` admin app for customers whose operations scripts
> depend on it. It will be removed in a future BLADE release. Migrate to
> the [Configurator](../configurator/) when your scripts are ready to
> publish changes via the UI's explicit Save + Publish flow.

## What it does

`watcher` is a headless WAR with a single background `WatchService`
thread. It watches `./config/custom/vorpal/` (and the per-cluster /
per-server subdirectories) for `*.json` file changes. When a change is
detected, it triggers a `SettingsMXBean.reload()` via JMX so the live
service picks up the new config — exactly the behavior the old console
app provided.

There is **no UI**, no servlets, no login page, no save endpoint, no
version history. There is nothing to log into. The only output is log
lines in the AdminServer log.

## Why it exists

The Configurator deliberately removed the auto-publish daemon: editing
config files outside the UI no longer triggers an automatic reload.
Operators must explicitly click Save + Publish in the Configurator UI for
changes to go live. This was a deliberate design choice — explicit user
control over what gets published when.

But: many existing customers have ops scripts that edit config files
directly and rely on the old auto-publish behavior. They cannot upgrade
to the Configurator without rewriting all those scripts. `watcher`
preserves the legacy behavior so they can upgrade incrementally:

1. Deploy `watcher` alongside the Configurator. Existing scripts
   keep working unchanged.
2. Migrate scripts at their own pace to use the Configurator's Save +
   Publish flow (or hand-edit then click Publish in the UI).
3. Once all scripts are migrated, undeploy `watcher`.

## Soft conflict with the Configurator

Both apps can be deployed at the same time. The Configurator has its own
Save + Publish flow; `watcher` independently watches the same
filesystem. If you save a file via the Configurator, `watcher`
will *also* see the change and trigger an extra publish — one redundant
reload, no functional harm.

There is **no enforced mutex**. Documentation only.

## Where it must be deployed

To the **AdminServer**, not to a managed cluster engine. The JMX MBean
lookups for `SettingsMXBean` resolve through `java:comp/env/jmx/domainRuntime`,
which is only available on the AdminServer.

## Migration path

When all your operations scripts use the Configurator's Save + Publish
flow (or hand-edit followed by an explicit publish via the Configurator
UI), undeploy `watcher`. The Configurator alone is sufficient
going forward.
