# BLADE overload

`blade-overload.jar` lets the options service tell OCCAS when an engine should refuse new
calls. The options service watches how long the engine takes over its SIP messages and decides; this JAR hands the decision to
OCCAS overload protection, which answers new requests from outside a dialog with `503 Server
busy` while it holds. Calls already up carry on.

OCCAS's own thresholds have one value each, so a reading sitting at the line switches refusing on
and off with every sample, and they are set in `sipserver.xml`, apart from the rest of BLADE's
configuration. Here the decision lives in `options.json` as one number, how slow the engine may
get, and changes in the Configurator without a restart. OCCAS sees 1 or 0, and one threshold
between them that never needs tuning.

## Installing it

Once per domain:

```
./deploy.sh <env> blade-overload.jar --domain-lib   # to <domain>/lib on the admin host
./install.sh <env> overload                         # names the collector in sipserver.xml
```

Each engine host needs the JAR in its own copy of `<domain>/lib`; `install.sh` carries it to
the hosts it provisions. Restart the AdminServer, then the engines: OCCAS finds the collector
only when a server starts.

A new domain made by `install.sh` gets the `sipserver.xml` part already. The settings are
`overload` in the options service's configuration; see `services/options/README.md`.

## When something is missing

A verdict lasts a few seconds unless the options service renews it, so an undeployed or
stopped options service leaves the engine taking calls, guarded only by the container's own
transport throttle. Without the JAR, or without the `sipserver.xml` part, nothing refuses
early. The dashboard says so in words on the engine that crossed the line, so a missing piece
shows the first time it matters.
