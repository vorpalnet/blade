# Vorpal:BLADE Access Control Lists (ACL)

[Javadocs](https://vorpalnet.github.io/vorpal-blade-acl/index.html)

The ACL application offers a way to allow or deny messages from external systems based on
their remote IP address.

Example config file:

```
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
