# BLADE Watcher (Headless Auto-Publish)

> **Standalone, minimal-attack-surface alternative to the Configurator's
> auto-publish.** This WAR was briefly retired when the
> [Configurator](../configurator/) absorbed its behavior behind the
> `autoPublish` setting, then restored: some sites cannot deploy the
> Configurator UI at all (it doesn't pass their security scans), and
> `watcher` — no UI, no servlets, no login — is what they deploy instead.
> It is deliberately **not** bundled into `blade-admin.ear`; deploy
> `blade-watcher.war` standalone. If you do run the Configurator, use its
> `autoPublish` setting and leave this WAR undeployed.

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

Two audiences:

1. **Sites that cannot deploy the Configurator.** The Configurator is a
   full web application (servlets, login page, JavaScript form editor) and
   may not pass a customer's security scanning. `watcher` exposes nothing
   scannable — no HTTP endpoints beyond the static docs pages, no
   authentication surface, no save endpoint. These sites deploy `watcher`
   alone and keep editing config files with their ops scripts.
2. **Sites migrating to the Configurator incrementally.** Existing ops
   scripts that edit config files directly keep working unchanged while
   operators adopt the Configurator's Save + Publish flow at their own
   pace. (The Configurator's own `autoPublish` setting covers this case
   too — pick one or the other, not both.)

## Soft conflict with the Configurator

Both apps can be deployed at the same time. The Configurator now has its
own auto-publish thread (the `autoPublish` setting) watching the same
filesystem; with both active, every file edit publishes twice — one
redundant reload, no functional harm. If you deploy `watcher` alongside
the Configurator, turn the Configurator's Auto-publish off.

There is **no enforced mutex**. Documentation only.

## Where it must be deployed

To the **AdminServer**, not to a managed cluster engine. The JMX MBean
lookups for `SettingsMXBean` resolve through `java:comp/env/jmx/domainRuntime`,
which is only available on the AdminServer.

## Configuration

The watcher reads `./config/custom/vorpal/watcher.json` at startup:

| Field     | Type    | Default | Meaning |
|-----------|---------|---------|---------|
| `enabled` | boolean | `true`  | When `false`, the WAR stays deployed but the watch thread is never started — the WAR is inert. Useful for silencing auto-publish during a maintenance window without an undeploy. |

The flag is read **once at startup**. Flipping it requires the
AdminServer to be restarted (or the WAR redeployed). There is no hot
toggle — by design: a runtime toggle would mean an HTTP endpoint, and
having none is the point of this WAR.

The same auto-publish behavior is also available inside the
[Configurator](../configurator/) via its `autoPublish` setting. If your
site runs the Configurator, use that and leave `watcher` undeployed.
