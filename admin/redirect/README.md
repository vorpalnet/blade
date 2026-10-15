# Redirect

The virtual host's default web application. It 302-redirects `/` and `/blade` to
`/blade/dashboard/`, so operators can type the bare hostname and land on the
[dashboard](../../proto/dashboard/README.md), whose rail lists every admin app and service.
Any other path no application on the server claims gets a 404 page that says so and links to
the dashboard. It used to redirect those as well, which made a link to an engine-tier app on
the admin address look like a link to the dashboard.

Two details worth knowing:

- It uses a servlet mapped to `/*` rather than a welcome-file, because a welcome-file
  catches `/` but 404s on `/blade`.
- It carries a loop guard: if the request is already for `/blade/dashboard` and still reached
  this app, the dashboard WAR isn't deployed; it answers `503 "The BLADE dashboard is not
  available."` instead of redirecting forever.

No Java dependencies, no configuration, no auth, no rail entry — its context-root is `/`,
and only one default web application is allowed per virtual host. WebLogic's
longest-prefix matching still routes `/blade/portal/`, `/blade/configurator/`, and the
rest to their own WARs.

## Related modules

- [proto/dashboard](../../proto/dashboard/README.md) — where the bare address redirects to
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-redirect</artifactId>
```
