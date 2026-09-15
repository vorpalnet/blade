# Admin EAR (`blade-admin.ear`)

Bundles every BLADE admin webapp into one deployable for the AdminServer, so the whole
admin tier deploys in one step. The EAR is purely a packaging convenience: it bundles no
libraries — each WAR inside is self-contained (its own framework JAR, referencing
[blade-shared](../../libs/shared/README.md) through its own `weblogic.xml`).

## How it's assembled

One Maven profile per WAR (`ear-portal`, `ear-configurator`, …), each active unless
`build.sh` sets `-Dskip.<name>`, so an app left out of a build selection (`build.apps` in
`./build.conf`) drops cleanly out of the EAR. The `javadoc` WAR rides the `ear-javadoc`
profile and joins the EAR on a `--prod` build.

Context roots are restated per module here because `application.xml` outranks each WAR's
`weblogic.xml` inside an EAR. **They are fixed deployment identifiers — never change
them.**

## What's inside

[portal](../../admin/portal/README.md) (`blade/portal`) ·
[redirect](../../admin/redirect/README.md) (`/`) ·
[configurator](../../admin/configurator/README.md) (`blade/configurator`) ·
[api](../../admin/api/README.md) (`blade/api`) ·
[flow](../../admin/flow/README.md) (`blade/flow`) ·
[crud-editor](../../admin/crud-editor/README.md) (`blade/crud-editor`) ·
[files](../../admin/files/README.md) (`blade/files`) ·
[tuning](../../admin/tuning/README.md) (`blade/tuning`) ·
[logs](../../admin/logs/README.md) (`blade/logs`) ·
[metrics](../../admin/metrics/README.md) (`blade/metrics`) ·
[callflow](../../admin/callflow/README.md) (`blade/callflow`) ·
[phone](../../admin/phone/README.md) (`blade/phone`) ·
[analytics-console](../../admin/analytics-console/README.md) (`blade/analytics`) ·
[events-console](../../admin/events-console/README.md) (`blade/events`) ·
[javadoc](../../admin/javadoc/README.md) (`blade/javadoc`, `--prod` builds only)

Not bundled: the `proto/` incubator apps ([security](../../proto/security/README.md),
[test-console](../../proto/test-console/README.md)) — promotion adds their `ear-<name>`
profile here.

## The other tiers

The test tier has its own EAR ([apps/test](../test/README.md), `blade-test.ear`), and the
services tier builds `blade-services.ear` from `apps/services`. Service WARs also ship
loose, so deploy either the EAR or the individual WARs (see [DEPLOYING.md](../../DEPLOYING.md)).
A build conf can turn a tier's EAR off with `ear.<tier>=off`.

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-ear</artifactId>
```
