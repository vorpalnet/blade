# Presence Service

Javadocs: `/blade/javadoc/presence/` on the Admin Portal

A skeleton SIP/SIMPLE presence service. It accepts SUBSCRIBE and PUBLISH, answers 200 OK
echoing the request's Expires, and rejects anything else with 500. It does not send
NOTIFY, keep a subscriber list, or store presence documents.

The session model is in place: a `@SipApplicationKey` selector keys every request by the
presentity (the To header's `user@host`, lowercased), so all SUBSCRIBE and PUBLISH
traffic for one presentity converges on a single `SipApplicationSession`.

`PresenceServlet` extends the framework's `v3.AsyncSipServlet`; the two callflows extend
`v3.Callflow`.

## Configuration

A single placeholder setting, edited through the
[Configurator](../../admin/configurator/README.md). There is no sample config.

## Related modules

- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — the base classes in use
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-presence</artifactId>
```
