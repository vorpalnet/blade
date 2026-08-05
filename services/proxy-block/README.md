# Proxy Block Service

Javadocs: `/blade/javadoc/proxy-block/` on the Admin Portal

A rule-driven SIP proxy that routes calls by calling number and dialed number: selectors
capture the From, To, and Request-URI; a translation table maps calling numbers (and,
nested under each, dialed numbers) to `forwardTo` targets built from `${...}` captures.

One thing to know up front: **despite the name, the shipped rule engine translates and
forwards — a deny/reject action is not implemented yet.** A call that matches no rule
follows the `defaultRoute` (by default, a pass-through of the request URI). Treat
"blocking" as the roadmap; number-based routing is what it does today.

## How it works

`ProxyBlockerServlet` extends the framework's v2 `ProxyServlet` — this is a true SIP
proxy, not a B2BUA; it decides the route on the initial request and stays out of the
media path. Its SIP application name is **`block`** (set explicitly in the annotation),
which is the name the application router must reference.

Routing is two lookups: calling number → translation, then dialed number → an override
within that translation, if one is defined. When a `forwardTo` list has several targets,
one is chosen at random per call. Captured groups from the selectors
(`${fromUser}`, `${ruriHost}`, …) substitute into the target template.

## Configuration

Configs are authored in a simple list form (`SimpleBlockConfig`) — readable, and what the
[Configurator](../../admin/configurator/README.md) edits — and compiled on every load
into a hash-map form for constant-time lookup at 1000+ CPS. The sample config shows the
full shape: the three selectors with named-capture patterns (including stripping a
leading `+`/`1` from the calling number), per-calling-number rules, per-dialed-number
overrides, and a templated `defaultRoute`.

## Related modules

- [proto/acl](../../proto/acl/README.md) — IP-level allow/deny, the network-edge complement
- [services/irouter](../irouter/README.md) — the universal config-driven proxy, when routing needs more than number tables
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-proxy-block</artifactId>
```
