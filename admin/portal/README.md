# Admin Portal

Javadocs: `/blade/javadoc/portal/` on the Admin Portal

The shared host behind every admin app, served at `/blade/portal`. Its launcher deck moved to
the [dashboard](../../proto/dashboard/README.md)'s rail: `/blade/portal/` now forwards there,
and the server's bare address goes there too ([Redirect](../redirect/README.md)). What stays
here is what the other apps load from it: the login page, the brand assets, the editor kit, the
card list the rail reads, the deployment inventory report and the service pages.

## The card list

`PortalCardsResource` (`/blade/portal/api/v1/cards`) walks two JMX surfaces on every
request; the dashboard's rail draws it:

- **App cards** — WebLogic's `WebApplicationRuntimeMBean`s, kept when the context-root
  starts with `blade/` and the app is active. This is why every admin app's context-root
  must start with `blade/`.
- **Service cards** — BLADE `Configuration` MBeans registered from the engine cluster.
  A service joins the deck once its config class carries `@SchemaAbout`; the card shows
  the schema's title, tagline, and description, and links to the
  [Configurator](../configurator/README.md).

The join key is the last segment of the context-root. An app with no `@SchemaAbout` still
gets a bare card named from its context-root slug. Each app card also carries `roles`: the
roles its `web.xml` security constraints admit, read from the deployed archive, so the rail can
say what a person must be granted to open it.

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
