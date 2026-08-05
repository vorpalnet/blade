# Admin Portal

Javadocs: `/blade/javadoc/portal/` on the Admin Portal

The unified admin shell — a launcher for every admin app deployed on the AdminServer,
served at `/blade/portal`. The card deck is built **live from JMX**: any app that
registers a `SettingsMXBean` shows up automatically, with no portal redeploy and no
manifest to maintain.

## How the deck is built

`PortalCardsResource` walks two JMX surfaces on every request:

- **App cards** — WebLogic's `WebApplicationRuntimeMBean`s, kept when the context-root
  starts with `blade/` and the app is active. This is why every admin app's context-root
  must start with `blade/`.
- **Service cards** — BLADE `Configuration` MBeans registered from the engine cluster.
  A service joins the deck once its config class carries `@SchemaAbout`; the card shows
  the schema's title, tagline, and description, and links to the
  [Configurator](../configurator/README.md).

The join key is the last segment of the context-root. An app with no `@SchemaAbout` still
gets a bare card named from its context-root slug.

## What else lives here

- The **master `login.jsp`**. Every other admin WAR injects this one file at build time —
  never add a per-app login page (see [SECURITY.md](../../SECURITY.md)).
- The brand assets under `/brand/*` that other admin apps reference.
- The **shared editor kit** under `/lib/*` (`blade-editor.js` + `blade-editor.css`):
  schema-driven form rendering, polymorphic card lists, and the help-dialog helper
  used by the purpose-built config editors
  ([crud-editor](../crud-editor/README.md), [irouter-editor](../irouter-editor/README.md)).
- A small config API the shell uses to show a service's running configuration.

## Configuration

`./config/custom/vorpal/portal.json` — metadata only. It exists so the portal itself
registers a Configuration MBean and appears in the Configurator's dropdown like everything
else.

## Related modules

- [admin/redirect](../redirect/README.md) — 302s bare `/` and `/blade` here
- [admin/configurator](../configurator/README.md) — where service cards link to
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-portal</artifactId>
```
