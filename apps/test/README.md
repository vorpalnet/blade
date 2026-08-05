# Test EAR (`blade-test.ear`)

A single deployable for the standalone engine0 test server: every BLADE service —
including the wide-open [proxy-registrar](../../services/proxy-registrar/README.md) —
plus the test harness apps, in one EAR. **Never deployed to the production cluster**; the
`production` build profile excludes it.

Like [the admin EAR](../admin/README.md), it bundles no libraries — every WAR inside is a
self-contained skinny WAR referencing [blade-shared](../../libs/shared/README.md). One
Maven profile per WAR (`ear-<name>`), so whatever the active build profile skips drops out
of the EAR cleanly.

## What's inside

Services: [analytics](../../services/analytics/README.md) ·
[context](../../services/context/README.md) ·
[crud](../../services/crud/README.md) ·
[hold](../../services/hold/README.md) ·
[irouter](../../services/irouter/README.md) ·
[options](../../services/options/README.md) ·
[presence](../../services/presence/README.md) ·
[proxy-balancer](../../services/proxy-balancer/README.md) ·
[proxy-block](../../services/proxy-block/README.md) ·
[proxy-registrar](../../services/proxy-registrar/README.md) ·
[queue](../../services/queue/README.md) ·
[tpcc](../../services/tpcc/README.md) ·
[transfer](../../services/transfer/README.md)

Test apps: [test-b2bua](../../test/test-b2bua/README.md) ·
[test-uac](../../test/test-uac/README.md) ·
[test-uas](../../test/test-uas/README.md)

Each keeps its normal flat context-root (`hold`, `context`, `test-uac`, …), so the same
SIPp scenarios and REST calls work against engine0 and a production engine unchanged.

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-test-ear</artifactId>
```
