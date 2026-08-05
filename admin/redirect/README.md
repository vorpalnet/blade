# Redirect

The virtual host's default web application. It 302-redirects `/`, `/blade`, and any
otherwise-unowned path to `/blade/portal/`, so operators can type the bare hostname and
land on the [Admin Portal](../portal/README.md).

Two details worth knowing:

- It uses a servlet mapped to `/*` rather than a welcome-file, because a welcome-file
  catches `/` but 404s on `/blade`.
- It carries a loop guard: if the request is already for `/blade/portal` and still reached
  this app, the portal WAR isn't deployed — it answers `503 "BLADE portal is not
  available."` instead of redirecting forever.

No Java dependencies, no configuration, no auth, no portal card — its context-root is `/`,
and only one default web application is allowed per virtual host. WebLogic's
longest-prefix matching still routes `/blade/portal/`, `/blade/configurator/`, and the
rest to their own WARs.

## Related modules

- [admin/portal](../portal/README.md) — where everything redirects to
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-redirect</artifactId>
```
