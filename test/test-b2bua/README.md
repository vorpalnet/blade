# Test B2BUA

Javadocs: `/blade/javadoc/test-b2bua/` on the Admin Portal

The starter B2BUA — the first app to read when you begin writing BLADE, and the one most
developers copy to start a new project. It is a working back-to-back user agent that links
two call legs and passes the call through unchanged. It carries no business logic, so
nothing hides the one thing it exists to teach: **how a whole SIP conversation becomes
readable, top-to-bottom code.**

It is written by hand on `AsyncSipServlet`, the framework's base servlet — not on the
pre-built `B2buaServlet`. That is deliberate. `B2buaServlet` already does everything this
app does; extend it and you write a few callbacks and never see the machinery. Here you
write the machinery yourself, once, so the model is in your hands before you let the
framework hold it for you.

## The one idea

A B2BUA is two legs — the caller's and the callee's — and the job is to keep them in step:
Alice's INVITE out to Bob, Bob's 200 OK back to Alice, Alice's ACK on to Bob.

Classic SIP Servlet code scatters that one exchange across three handlers — `doInvite`,
`doResponse`, `doAck` — and a `doResponse` that must ask *a response to what?* every time it
runs. You end up hand-rolling state variables to track where each dialog stands. It reads
like a choose-your-own-adventure book.

BLADE writes the same exchange as one method that reads in the order the messages arrive.
`sendRequest` sends a message and hands the response to your lambda when it arrives;
`sendResponse` sends a response and hands you the ACK. Between calls the callflow's state
serializes into the SIP session and the method returns — no thread waits. When the peer's
message arrives, the framework rehydrates that state on whichever cluster node the container
routed it to and runs your lambda. That is the failover story: the call survives a node
dropping because its place in the conversation lives in the replicated session, not on a
thread's stack.

## The shape of a BLADE app

A BLADE application is a servlet that **dispatches** and callflows that do the **work**:

| File | Role |
|---|---|
| `TestB2buaSipServlet` | Extends `AsyncSipServlet`. `chooseCallflow` maps each inbound method to a callflow; `servletCreated` starts the config manager. |
| `TestB2buaInvite` | The INVITE callflow — the nested-lambda exchange that sets up the call. The heart of the app. |
| `TestB2buaCancel` | Forwards a CANCEL to the outbound leg. |
| `TestB2buaPassthru` | Everything else (BYE, INFO, OPTIONS, …): forward the request, return the response. |
| `TestB2buaConfiguration` / `TestB2buaSettingsManager` | The config shape and its manager (seed + reload hook). |

## The servlet

```java
public class TestB2buaSipServlet extends AsyncSipServlet {

    public static TestB2buaSettingsManager settingsManager;

    @Override
    protected Callflow chooseCallflow(SipServletRequest inboundRequest) {
        switch (inboundRequest.getMethod()) {
        case Callflow.INVITE:  return new TestB2buaInvite();
        case Callflow.CANCEL:  return new TestB2buaCancel();
        default:               return new TestB2buaPassthru();
        }
    }

    @Override
    public void servletCreated(SipServletContextEvent event) {
        settingsManager = new TestB2buaSettingsManager(event);
    }
}
```

Four annotations on the class carry their weight — `@SipApplication(distributable = true)`
replicates SIP sessions across the cluster (the failover guarantee the serialized state
rides on), `@SipServlet(loadOnStartup = 1)` initializes on deploy, and `@WebListener` /
`@SipListener` receive the servlet-context and SIP-session lifecycle events.

`chooseCallflow` runs for each initial request — it is the whole routing table for the app,
in one `switch`. `AsyncSipServlet` handles the mid-dialog plumbing: an ACK, for instance, is
delivered straight to the pending `sendResponse` lambda rather than routed here.

## The INVITE callflow

`TestB2buaInvite` is the exchange, and it is short enough to read whole:

```java
public void process(SipServletRequest aliceRequest) throws ServletException, IOException {

    SipServletRequest bobRequest = createRequest(aliceRequest);
    sendRequest(bobRequest, (bobResponse) -> {

        SipServletResponse aliceResponse = createResponse(aliceRequest, bobResponse);
        sendResponse(aliceResponse, (aliceAck) -> {

            SipServletRequest bobAckOrPrack = createAcknowledgement(bobResponse, aliceAck);
            sendAcknowledgement(bobAckOrPrack, bobResponse);
        });
    });
}
```

Four v3 helpers do the building, so the callflow stays this small:

- **`createRequest(aliceRequest)`** — clones Alice's INVITE into Bob's, copies content and
  headers, and links the two SIP sessions. On a re-INVITE (the sessions already linked) it
  builds the request in-dialog instead.
- **`createResponse(aliceRequest, bobResponse)`** — builds Alice's response from Bob's,
  copies content and headers, and completes the session link in the other direction.
- **`createAcknowledgement(bobResponse, aliceAck)`** — derives the right acknowledgement,
  ACK or PRACK, to send toward Bob.
- **`sendAcknowledgement(bobAckOrPrack, bobResponse)`** — sends it.

### Two things the code doesn't show, but the call flow does

- **The `sendRequest` lambda fires more than once.** A 180 Ringing arrives, then a 200 OK;
  your lambda runs for each, building whichever response back to Alice. You never test
  provisional versus final — you handle the message you were handed. When the ACK returns
  through `sendResponse`, the transaction is complete and no further responses arrive.
- **The link is completed on the response, not the request, on purpose.** `createRequest`
  links Alice to Bob one way; `createResponse` completes it the other. The gap is deliberate:
  you may fan out several outbound INVITEs at once, but only one answers — the leg whose
  response you build back to Alice is the one that gets linked, and the losers are discarded.

With PRACK in the network, the same doubling reaches deeper: `sendResponse` can hand you a
PRACK and then the ACK, and `sendAcknowledgement` runs for each. `TestB2buaInvite`'s javadoc
walks the sequence line by line.

## The other two callflows

**`TestB2buaCancel`** forwards a cancellation to the outbound leg:

```java
SipServletRequest bobCancel = createCancel(aliceCancel);
sendRequest(bobCancel);
```

A CANCEL can't be cloned with `createRequest` — it has to be derived from the INVITE it
cancels, which is what `createCancel` does. There is no response lambda because the container
answers a CANCEL with 200 OK on its own.

**`TestB2buaPassthru`** handles every other method — BYE, INFO, OPTIONS, a re-INVITE's
mid-dialog kin. It forwards the request and returns the response, branching only to log
whether Bob's reply was provisional, successful, or a failure. A BYE runs through here: the
request goes to Bob, his 200 OK comes back to Alice, and no ACK is expected.

## Configuration

`TestB2buaConfiguration` is the config's shape — three fields (`traveler`, `quest`, `color`),
each carrying a `@JsonPropertyDescription` that becomes help text in the Configurator form.
The form is generated from the class; you never hand-build it.

`TestB2buaSettingsManager` extends the v3 `SettingsManager` and supplies the two things a
manager must:

- **`sample()`** — the fully-populated seed written to a fresh config file on first deploy.
- **`refreshed(config)`** — run on the initial load and on every change pushed through the
  Configurator or JMX. Both are abstract in the framework: a config always deserves an
  explicit seed, and the reload hook is the piece developers overlook — so the contract puts
  both in front of you.

One line names the config type — `extends SettingsManager<TestB2buaConfiguration>` — and the
framework recovers it reflectively; there is no `Class<T>` token to repeat. The `(event)`
constructor loads the config from the three-tier hierarchy, generates the JSON Schema,
registers the JMX reload MBean, and starts logging.

## When to graduate to `B2buaServlet`

Once the model is yours, you rarely write these callflows by hand again. The framework's
`B2buaServlet` packages exactly this set — an INVITE callflow, a terminate path, a passthru —
already written and hardened for what a textbook leaves out: a CANCEL and a 200 OK racing on
the wire, re-INVITEs, PRACK, redirects. You extend it and implement six lifecycle callbacks —
`callStarted`, `callAnswered`, `callConnected`, `callCompleted`, `callDeclined`,
`callAbandoned` — where your logic goes. This app is what those callbacks sit on top of. Read
it once; then let the framework hold it.

## Building and running

Builds with everything else (`./build.sh`) and deploys as `test-b2bua.war`
(context-root `test-b2bua`), normally via the test EAR from
[apps/test](../../apps/test/README.md).

Drive it with its own siblings: [test-uac](../test-uac/README.md) originates calls and
[test-uas](../test-uas/README.md) answers them, so a call runs Alice → **test-b2bua** → Bob
end to end. Point test-uac at test-b2bua with test-uas behind it, place a call, and watch the
lifecycle log trace the exchange leg by leg. Standalone SIPp scenarios live in `testing/`
for driving the app without the pair.

## Related modules

- [test-uac](../test-uac/README.md) / [test-uas](../test-uas/README.md) — the SIP load-testing pair that drives this app
- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — `AsyncSipServlet`, `Callflow`, `SettingsManager`, and the pre-built `B2buaServlet`
- [Callflow model (v2 guide)](../../libs/framework/src/main/java/org/vorpal/blade/framework/v2/callflow/README.md) — the fullest walkthrough of the lambda exchange
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>test-b2bua</artifactId>
```
</content>
