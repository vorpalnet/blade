# Vorpal:BLADE

The transfer service will perform a 'blind' transfer when it receives a REFER and a matching condition is met.

Sample 'transfer.json' configuration file:

```
{
  "transferAllRequests" : false,
  "defaultTransferStyle" : "blind",
  "preserveInviteHeaders" : [ "Cisco-Gucid", "User-to-User" ],
  "transferConditions" : [ {
    "style" : "blind",
    "condition" : {
      "OSM-Features" : [ {
        "includes" : "transfer"
      } ]
    }
  }, {
    "style" : "blind",
    "condition" : {
      "To" : [ {
        "matches" : ".*sip:1996.*"
      } ]
    }
  } ]
}
```

Note: Additional 'assisted' and 'media' transfer callflows are in the works, but have not been completed.
