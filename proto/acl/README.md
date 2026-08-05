# ACL Service

The ACL application allows or denies calls based on the remote IP address they arrive
from — a network-edge gate in front of the rest of the cluster.

Example config file:

```json
{
  "defaultPermission" : "deny",
  "remoteAddresses" : [ {
    "address" : "192.168.1.0/24",
    "permission" : "allow"
  }, {
    "address" : "192.168.2.136",
    "permission" : "deny"
  } ]
}
```

Addresses take single IPs or CIDR ranges; `defaultPermission` decides everything that
matches no rule. Edit and publish through the
[Configurator](../../admin/configurator/README.md).

## Incubator status

This module lives in `proto/` — it builds under the `full` profile (WAR: `acl.war`) but is
excluded from the everyday `default`/`production` builds. Promotion moves it to
`services/`.

## Related modules

- [services/proxy-block](../../services/proxy-block/README.md) — rule-based call blocking above the IP layer
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-acl</artifactId>
```
