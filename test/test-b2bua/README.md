# Test B2BUA

Javadocs: `/blade/javadoc/test-b2bua/` on the Admin Portal

A sample B2BUA that is both a functional test and a reference implementation — the
template most BLADE developers copy when starting a new B2BUA project. It implements no
business logic: calls pass through as a transparent back-to-back user agent while every
lifecycle event is logged.

It earns its keep three ways:

- **Verification** — confirms the B2BUA framework works on a given OCCAS deployment.
- **Template** — demonstrates the exact annotations, initialization pattern, and callback
  structure a real application needs.
- **Debugging aid** — the lifecycle logging makes the call flow traceable end to end.

## What to look at

`SampleB2buaServlet` extends the framework's `v3.B2buaServlet`. Its `chooseCallflow()`
override is the marked "put your custom callflows here" hook (it currently defers to the
framework's standard callflows), and all six lifecycle callbacks — `callStarted`,
`callAnswered`, `callConnected`, `callCompleted`, `callDeclined`, `callAbandoned` — show
where application logic belongs. See the [v2 b2bua guide](../../libs/framework/src/main/java/org/vorpal/blade/framework/v2/b2bua/README.md)
for what each callback means and the
[v3 API README](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md)
for what the v3 base class adds.

`CancelGlare` is a deliberately misbehaving callflow: it suppresses a CANCEL and answers
200 OK instead, manufacturing the race where a CANCEL and a 200 OK cross on the wire — the
case the framework's `CallflowAckBye` is designed to survive.

The sample config asks the questions three — `traveler`, `quest`, `color` — and logs the
Bridge of Death challenge at startup. It doubles as a minimal example of a
`SettingsManager` config class whose sample needs the `SipFactory` to build SIP addresses.

## Running it

Deploys as `test-b2bua.war` (context-root `test-b2bua`), normally via the test EAR from
[apps/test](../../apps/test/README.md); the `production` build profile excludes it.

Drive it with SIPp from `testing/glare/`: `uas.sh` starts the answering side, `uac.sh` /
`glare-uac.sh` originate normal and glare-provoking calls. The scripts carry lab IP
addresses — edit the variables at the top for your environment. For load rather than
scenarios, use [test-uac](../test-uac/README.md) and [test-uas](../test-uas/README.md).

## Related modules

- [test-uac](../test-uac/README.md) / [test-uas](../test-uas/README.md) — the SIP load-testing pair
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>test-b2bua</artifactId>
```
