# Presence Service

Javadocs: `/blade/javadoc/presence/` on the Admin Portal

The starting point for a SIP/SIMPLE presence server. **Today it is a skeleton**: it
accepts SUBSCRIBE and PUBLISH, answers 200 OK echoing the request's Expires, and rejects
anything else with 500. It does not yet send NOTIFY, keep a subscriber list, or store
presence documents — the intended event model is sketched in the source (`Event.java`)
and waiting to be built.

What the skeleton already gets right is the session model: a `@SipApplicationKey`
selector keys every request by the presentity (the To header's `user@host`, lowercased),
so all SUBSCRIBE and PUBLISH traffic for one presentity converges on a single
`SipApplicationSession`. That is the anchor a real subscriber list and NOTIFY fan-out
will hang from.

`PresenceServlet` extends the framework's `v3.AsyncSipServlet`; the two callflows extend
`v3.Callflow`.

## Configuration

A single placeholder setting, edited through the
[Configurator](../../admin/configurator/README.md). No sample config yet.

## Related modules

- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — the base classes in use
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-presence</artifactId>
```
