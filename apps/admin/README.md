# Admin EAR (`blade-admin.ear`)

Bundles every BLADE admin webapp into one deployable for the AdminServer, so the whole
admin tier deploys in one step. The EAR is purely a packaging convenience: it bundles no
libraries — each WAR inside is self-contained (its own framework JAR, referencing
[blade-shared](../../libs/shared/README.md) through its own `weblogic.xml`).

## How it's assembled

One Maven profile per WAR (`ear-portal`, `ear-configurator`, …), each active unless the
build profile sets `-Dskip.<name>` — so a module skipped by the active
`build-profiles/*.conf` drops cleanly out of the EAR. The `javadoc` WAR rides the
`javadocs` profile instead, joining the EAR whenever javadocs are built.

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
[javadoc](../../admin/javadoc/README.md) (`blade/javadoc`, javadocs builds only)

Not bundled: the `proto/` incubator apps ([security](../../proto/security/README.md),
[test-console](../../proto/test-console/README.md)) — promotion adds their `ear-<name>`
profile here.

## The other tiers

The test tier has its own EAR ([apps/test](../test/README.md), `blade-test.ear`).
**There is deliberately no services EAR** — Oracle's Remote Console can't show the status
of an app inside an EAR, so service WARs deploy individually to the cluster (see
[DEPLOYMENT.md](../../DEPLOYMENT.md)).

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-ear</artifactId>
```
