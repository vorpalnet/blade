# Detached SIP objects

Javadocs: package `org.vorpal.blade.framework.sip` — browse at `/blade/javadoc/framework/` on the Admin Portal

Implementations of the `javax.servlet.sip` interfaces that are **detached** — no
transaction, no dialog, no socket, no container behind them. A `DetachedResponse`
is a real `SipServletResponse` holding real headers and a real body; it simply was
not delivered by OCCAS.

The framework uses them in three places, and so can you:

- **Manufacturing a response locally.** When a callflow throws, `sendRequest`
  hands your callback a `DetachedResponse` carrying `500` and the exception —
  which is why a callflow never has to handle both an exception path and an
  error-response path.
- **Parsing SIP text into objects.** `v3.crud.SipMessageParser` turns a message
  template into a detached request or response the CRUD rules can be previewed
  against, and `SipMessageSerializer` renders it back to RFC 3261 text.
- **Testing without a container.** This is the big one for application authors,
  and the rest of this page is about it.

## Testing callflows

A `SipServlet` subclass cannot be instantiated outside OCCAS. **A callflow can.**
It is an ordinary Java object, and these classes stand in for the container
underneath it — so you can build an INVITE, run a callflow against it, hand it a
response, and assert on what came out, in a JUnit test that finishes in
milliseconds.

That is the practical split: keep decisions in callflows and plain classes, keep
the servlet thin, and you can test almost everything on your laptop.

## Setup

Three statics have to be installed before a callflow will run. They are the
container services `Callflow` reaches for, and JUnit gives each test class a
fresh JVM-wide copy, so set them up and tear them down:

```java
@BeforeEach
void installContainerStandIns() {
    Callflow.setSipFactory(new DetachedSipFactory());
    Callflow.setSipLogger(new CapturingLogger());
    Callflow.setSipUtil(new DetachedSipSessionsUtil());
}

@AfterEach
void removeContainerStandIns() {
    Callflow.setSipFactory(null);
    Callflow.setSipLogger(null);
    Callflow.setSipUtil(null);
}
```

Leave any of the three out and the failure is misleading rather than obvious:

| Missing | What you see |
|---|---|
| `SipFactory` | `NullPointerException` the first time a request is built |
| `Logger` | `NullPointerException` inside `linkSession`, before your code runs |
| `SipSessionsUtil` | your callback receives a **synthetic `500`** and the test reads like a rejected call |

The last one is worth knowing about. `sendRequest` stamps the Vorpal tracking
headers on every initial INVITE, and minting a Vorpal-ID asks the util whether
that id is already taken. With no util installed, the `NullPointerException` is
caught by `sendRequest`'s own error handling, which converts any exception into a
`500` response and hands it to your callback — deliberately, so production
callflows do not have to handle both exceptions and error responses. In a test it
looks like Bob declined the call.

## A worked example

The whole cycle: build the inbound INVITE, run the callflow, deliver a response,
assert. This is
[`CallflowHarnessSmokeTest`](../../../../../../../test/java/org/vorpal/blade/framework/sip/CallflowHarnessSmokeTest.java),
which runs in the suite — copy it rather than the fragments below.

The callflow under test, a minimal B2BUA leg:

```java
static class ForwardingCallflow extends Callflow {
    SipServletRequest outbound;
    SipServletResponse upstreamAnswer;

    @Override
    public void process(SipServletRequest aliceRequest) throws ServletException, IOException {
        outbound = createRequest(aliceRequest);
        sendRequest(outbound, (bobResponse) -> {
            upstreamAnswer = createResponse(aliceRequest, bobResponse);
            sendResponse(upstreamAnswer);
        });
    }
}
```

An inbound request, assembled the way the container would hand you one — note the
session, which nothing works without:

```java
DetachedApplicationSession appSession = new DetachedApplicationSession("harness");
DetachedRequest alice = new DetachedRequest(appSession, "INVITE");
alice.setSession(new DetachedSipSession(appSession));
alice.setRequestURI(new DetachedSipURI("sip:bob@example.com"));
alice.setHeader("X-Trace", "abc123");
alice.setContent(sdp.getBytes("UTF-8"), "application/sdp");
```

Run it, and assert on the outbound leg:

```java
ForwardingCallflow callflow = new ForwardingCallflow();
callflow.process(alice);

assertEquals("INVITE", callflow.outbound.getMethod());
assertEquals("abc123", callflow.outbound.getHeader("X-Trace"));   // non-system headers travel
assertNotNull(callflow.outbound.getRawContent());                  // so does the SDP offer
```

## Delivering a response

This is the one step a test does that production code never does. `DetachedRequest.send()`
goes nowhere, so no response ever comes back on its own. In OCCAS the container
matches an inbound response to its session and fires the callback `sendRequest`
stored there; in a test you do that yourself:

```java
private static void deliver(SipServletResponse response) throws Exception {
    Callback<SipServletResponse> callback = Callflow.pullCallback(response);
    assertNotNull(callback, "no callback was registered for this response");
    callback.acceptThrows(response);
}
```

Build the response against the **outbound** request, so it lands on the right
session, then deliver it:

```java
DetachedResponse bobAnswer = new DetachedResponse((DetachedRequest) callflow.outbound, 200, "OK");
bobAnswer.setHeader("X-Answered-By", "bob");
deliver(bobAnswer);

assertEquals(200, callflow.upstreamAnswer.getStatus());
assertEquals("bob", callflow.upstreamAnswer.getHeader("X-Answered-By"));
assertSame(alice, callflow.upstreamAnswer.getRequest());
```

`pullCallback` follows the same rules as the container: it removes the callback
for a final response (status ≥ 200) and keeps it for a provisional, so you can
deliver `180` then `200` through the same lambda and exercise a real ringing
sequence.

## What is in the kit

| Class | Stands in for | Notes |
|---|---|---|
| `DetachedSipFactory` | `SipFactory` | builds requests, URIs and addresses; refuses ACK and CANCEL exactly as OCCAS does |
| `DetachedSipSessionsUtil` | `SipSessionsUtil` | real lookups over registered application sessions |
| `DetachedApplicationSession` | `SipApplicationSession` | attribute store, session registry, index keys |
| `DetachedSipSession` | `SipSession` | attributes, state, `createRequest`, active-INVITE tracking |
| `DetachedMessage` | `SipServletMessage` | the shared base: headers, content, character encoding |
| `DetachedRequest` | `SipServletRequest` | `createResponse`, `createCancel`, routing directive, `isInitial` |
| `DetachedResponse` | `SipServletResponse` | status and reason, `createAck`, `createPrack`, reliable provisionals |
| `DetachedSipURI` | `SipURI` | full parse and render of `scheme:user@host:port;params` |
| `DetachedAddress` | `Address` | parses `"Alice" <sip:…>;tag=abc`, keeping header and URI parameters apart |

`DetachedSipURI` and `DetachedAddress` parse and render properly rather than storing a
string, which is what makes request-URI work testable — including the
`copyParameters` merge that carries the inbound user part onto a configured
destination. See
[`SipUriAndAddressSmokeTest`](../../../../../../../test/java/org/vorpal/blade/framework/sip/SipUriAndAddressSmokeTest.java).

## Limits

- **Nothing is sent.** `send()` is a no-op everywhere. Assert on the message
  objects your callflow built, and drive the conversation with `deliver`.
- **No timers fire.** `startTimer` records nothing here; a callflow that waits on
  a timer needs that branch invoked directly.
- **No container dispatch.** Call `process(request)` yourself. `chooseCallflow`
  lives on the servlet, which is the part that genuinely cannot run outside OCCAS.
- **These are detached objects, not a SIP stack.** They are as correct as the code
  using them demands. When one is wrong for your case, fix it — they are ordinary
  source in this package, and several methods became real implementations
  precisely because a test needed them to be. Note that the framework itself ships
  three of them at runtime, so a fix here is a change to production behaviour, not
  just to a test helper.

## Related

- [Framework Developer's Guide](../../../../../../../../../../DEVELOPING.md) — how callflows work
- [callflow guide](../v2/callflow/README.md) · [b2bua guide](../v2/b2bua/README.md)
