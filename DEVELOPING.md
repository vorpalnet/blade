# Framework Developer's Guide

How to write BLADE code.

This guide is about `sendRequest` and `sendResponse` — the two methods the whole
framework is built on. Everything else in BLADE is a variation on what they do.
If you understand them, you can read any callflow in the tree.

The worked examples are real code you can open alongside this:
[`InitialInvite`](libs/framework/src/main/java/org/vorpal/blade/framework/v2/b2bua/InitialInvite.java),
the callflow `B2buaServlet` runs for every initial INVITE, and
[`BlindTransfer`](libs/framework/src/main/java/org/vorpal/blade/framework/v2/transfer/BlindTransfer.java),
which the [transfer service](services/transfer/README.md) uses to move a call
from one party to another.

For packaging, building and deploying a service, see [§7](#7-packaging-a-service)
at the end — it is the least interesting part and it is mostly cross-references.

## Contents

- [Before you start: v2 or v3?](#before-you-start-v2-or-v3)
1. [The problem](#1-the-problem)
2. [sendRequest](#2-sendrequest)
3. [sendResponse](#3-sendresponse)
4. [Nesting: a callflow is a conversation](#4-nesting-a-callflow-is-a-conversation)
5. [What survives, and how](#5-what-survives-and-how)
6. [expectRequest: messages that may never come](#6-expectrequest-messages-that-may-never-come)
7. [Packaging a service](#7-packaging-a-service)
- [When something goes wrong](#when-something-goes-wrong)
8. [House rules](#8-house-rules)

---

## Before you start: v2 or v3?

**Writing something new? Use the table below and don't think about it again.**

The longer answer, because you will see two of some things. The framework is
consolidating onto one version-neutral package, `org.vorpal.blade.framework` — the
*baseline*. Work that used to live in `v2.*` and `v3.*` is moving there, and the old
names are being left behind as **faces**: four-line shells that extend the real class
so existing applications keep compiling. `v2.AsyncSipServlet` is one — its entire body
is a `serialVersionUID`, because the implementation is already in the baseline.

Faces are marked `@Deprecated`, so your IDE will strike through the ones you should
not be typing. There is only ever one implementation behind them; a `v2.Callback` and
a baseline `Callback` are the same type, and passing one where the other is expected
always works.

Where you still have to choose, **sixteen class names exist in both packages** —
`Callflow`, `Callback`, `AsyncSipServlet`, `B2buaServlet`, `Analytics`, `Selector`,
`Sdp` and more — and autocomplete will offer you both. What a new application wants:

| You want | Import |
|---|---|
| a callflow to extend | `org.vorpal.blade.framework.v3.Callflow` |
| a B2BUA servlet | `org.vorpal.blade.framework.v3.B2buaServlet` |
| a bare SIP servlet | `org.vorpal.blade.framework.AsyncSipServlet` |
| a lambda continuation | `org.vorpal.blade.framework.Callback` |
| **configuration** | `org.vorpal.blade.framework.v2.config.SettingsManager` |

That last row is not a typo, and it is the exception worth knowing. `v2.config.SettingsManager`
is imported by 124 files in this repo; the v3 one by a single service. The v3 version is a
different, more type-safe design that requires a subclass binding the config type
(`class FooManager extends v3.configuration.SettingsManager<FooConfig>`), and it is not yet
the default. Use the v2 one unless you have a reason.

Two rows point at the baseline rather than `v3.*`, which is the direction everything is
heading: the versioned name is the one that goes away.

The other exception: **the container-proxy API exists only in v2**
(`v2.callflow.Callflow.proxyRequest`). v3 answers the same need with passthru
drop-out on `sendRequest`, which is configuration-driven — but if you need a real
`javax.servlet.sip.Proxy`, v2 is where it lives.

Everything below is the same in both generations.

---

## 1. The problem

A SIP servlet is a callback machine. The container hands you `doInvite`,
`doResponse`, `doAck`, `doBye` — each one a separate method, each called at a
different moment, none of them knowing what the others saw.

Consider forwarding a call. INVITE arrives in `doInvite`; you build an outbound
INVITE and send it. Some time later a `180 Ringing` arrives in `doResponse`, and
you must work out which call it belongs to and what to do about it. Then a `200
OK` arrives — in the same `doResponse`, with nothing but session attributes to
tell it apart from the 180. Then an ACK arrives in `doAck`, and you must
reconstruct enough context to build the matching ACK for the other leg.

Response to *what*? You end up writing a state variable, then another, then a
switch over both. The logic of one conversation is smeared across four methods
and a bag of attributes, and reading it means jumping between pages like a
choose-your-own-adventure book.

BLADE's answer is to keep the conversation in one place:

```java
sendRequest(bobRequest, (bobResponse) -> {
    SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
    sendResponse(aliceResponse, (aliceAck) -> {
        sendRequest(bobResponse.createAck());
    });
});
```

Send an INVITE to Bob. When he answers, answer Alice. When Alice acknowledges,
acknowledge Bob. Four SIP transactions, one method, read top to bottom.

The question is how that actually works, because those lambdas do not run on the
thread that created them, may not run on the *machine* that created them, and
`aliceRequest` is somehow still in scope minutes later.

## 2. sendRequest

`sendRequest` replaces `SipServletRequest.send()`. Two forms:

```java
void sendRequest(SipServletRequest request, Callback<SipServletResponse> lambda)
void sendRequest(SipServletRequest request)
```

Stripped of glare handling and header stamping, the two-argument form does this
([`Callflow.java:935`](libs/framework/src/main/java/org/vorpal/blade/framework/Callflow.java)):

```java
request.getSession().setAttribute(RESPONSE_CALLBACK_ + request.getMethod(), lambdaFunction);
request.send();
```

**Your lambda is stored in the SIP session, keyed by the method you just sent.**
That is the entire trick. The request goes on the wire, the thread returns, and
the continuation waits in session memory under a name like
`RESPONSE_CALLBACK_INVITE`.

When a response arrives, the framework's dispatcher looks in that same slot
([`AsyncSipServlet.doResponse`](libs/framework/src/main/java/org/vorpal/blade/framework/AsyncSipServlet.java)
→ `Callflow.pullCallback`), finds your lambda, and invokes it with the response.
No `doResponse` to write, no correlation to do — the response found its
continuation because they share a session and a method name.

### Why the lambda fires more than once

This is the part that surprises people, and it falls out of four lines in
`pullCallback` ([`Callflow.java:297`](libs/framework/src/main/java/org/vorpal/blade/framework/Callflow.java)):

```java
if (response.getProxyBranch() == null && response.getStatus() >= 200) {
    sipSession.removeAttribute(attribute);
}
```

The callback is read on every response but **removed only when the status is 200
or above**. A `180 Ringing` leaves it in place, so the next response finds it
again. A `200 OK` — or any final response — takes it out, and the lambda is done.

So one `sendRequest` lambda receives every response to that request, in order,
ending with the final one. That is why real callflows branch on the response
class rather than assuming a single answer. From `BlindTransfer`:

```java
sendRequest(targetRequest, (targetResponse) -> {
    if (provisional(targetResponse)) {
        // Carol is ringing; nothing to do but log it
    } else if (successful(targetResponse)) {
        // Carol answered — connect her to Alice
    } else if (failure(targetResponse)) {
        // Carol declined, or Alice gave up (487)
    }
});
```

`provisional`, `successful`, `redirection` and `failure` are static helpers on
`Callflow`: 1xx, 2xx, 3xx and 400-or-above respectively. Note that 3xx belongs to
none of the three branches above — on an outbound INVITE the framework
auto-follows redirects for you, up to five hops, so your lambda normally sees the
final response from wherever the call ended up.

### The one-argument form

`sendRequest(request)` registers nothing, so the response is absorbed by the
framework and your code never sees it. That is the right choice when there is
genuinely nothing to decide — an ACK, or a BYE whose `200 OK` you do not care
about. `BlindTransfer` uses it for exactly those:

```java
sendRequest(transfereeResponse.createAck());
```

Do not write an empty lambda to "handle" a response you are going to ignore. The
absence of a lambda is the statement.

### Exceptions come back as responses

If sending throws, `sendRequest` does not propagate the exception. It builds a
synthetic `500` response naming the exception class, and hands it to your lambda
([`Callflow.java:959`](libs/framework/src/main/java/org/vorpal/blade/framework/Callflow.java)).
The comment in the source says why: *"It's too maddening to write callflows where
you have to worry about both error responses and exceptions."* Your failure
handling lives in one place — the `failure()` branch — and covers both a remote
`503` and a local `NullPointerException`.

## 3. sendResponse

`sendResponse` is the mirror image, and the asymmetry is worth understanding:
after you answer, the thing you wait for is not a response but a **request** —
the ACK.

```java
void sendResponse(SipServletResponse response, Callback<SipServletRequest> lambda)
void sendResponse(SipServletResponse response)
```

The lambda is stored under the ACK's request-callback key
([`Callflow.java:1477`](libs/framework/src/main/java/org/vorpal/blade/framework/Callflow.java)):

```java
response.getSession().setAttribute(REQUEST_CALLBACK_ + ACK, lambdaFunction);
```

So `sendResponse(aliceResponse, (aliceAck) -> {...})` reads as "answer Alice, and
when she acknowledges, do this." If the response is a reliable provisional, the
same lambda is also registered under `PRACK` and the response goes out via
`sendReliably()` — one lambda, whichever acknowledgement the far end chooses.

The single-argument form is *not* "no callback". It registers a lambda that does
nothing ([`Callflow.java:1523`](libs/framework/src/main/java/org/vorpal/blade/framework/Callflow.java)):

```java
public void sendResponse(SipServletResponse response) throws ServletException, IOException {
    sendResponse(response, (ackOrPrack) -> {
        // do nothing;
    });
}
```

That matters. A registered no-op absorbs the ACK; *no* registration would let the
ACK fall through to `chooseCallflow` as an unhandled request. Answering with
`sendResponse(request.createResponse(202))` — as `BlindTransfer` does for the
inbound REFER — quietly swallows the acknowledgement, which is what you want.

## 4. Nesting: a callflow is a conversation

Now the two compose, and nesting depth becomes conversational depth. Here is the
heart of `InitialInvite` — the callflow every `B2buaServlet` runs for an initial
INVITE. Logging, analytics events, listener null-checks, the caller/callee
session tagging, the `doNotProcess` escape hatch and the PRACK branch are all
elided; what remains is the shape:

```java
sendRequest(bobRequest, (bobResponse) -> {

    SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
    copyContentAndHeaders(bobResponse, aliceResponse);

    if (successful(bobResponse)) {
        b2buaListener.callAnswered(aliceResponse);
    } else if (failure(bobResponse)) {
        b2buaListener.callDeclined(aliceResponse);
    }

    sendResponse(aliceResponse, (aliceAck) -> {
        if (aliceAck.getMethod().equals(ACK)) {
            SipServletRequest bobAck = copyContentAndHeaders(aliceAck, bobResponse.createAck());
            b2buaListener.callConnected(bobAck);
            sendRequest(bobAck);
        }
    });
});
```

Read it as a sentence. *Invite Bob. Whatever he says, say the same thing to
Alice. When Alice acknowledges, acknowledge Bob.*

Every response Bob sends runs the outer lambda: the 180 becomes a 180 to Alice,
the 200 becomes a 200 to Alice. Only the 2xx and the failures fire a lifecycle
callback, because only they are decisions. The `sendResponse` inside the outer
lambda re-registers on each pass — harmless, because the ACK only ever arrives
after a 2xx.

That is a complete B2BUA. Three hundred lines of handler-and-attribute
bookkeeping, expressed as the shape of the conversation.

## 5. What survives, and how

`aliceRequest` is used inside a lambda that runs after a network round trip,
possibly on another machine. Here is why that works.

`Callback` extends both `Consumer` and **`Serializable`**
([`Callback.java`](libs/framework/src/main/java/org/vorpal/blade/framework/Callback.java)).
A serializable lambda drags its captured state with it. Storing it in a SIP
session attribute means the container serializes and replicates it exactly as it
would any other attribute — and because the application is declared
`distributable`, that replication spans the cluster.

Two things get carried, and the distinction matters when you write your own:

**Captured locals.** A lambda that mentions `bobResponse` captures that reference,
and it is serialized with the lambda.

**The callflow itself.** `Callflow implements Serializable`
([`Callflow.java:81`](libs/framework/src/main/java/org/vorpal/blade/framework/Callflow.java)),
and this is the subtler half. `InitialInvite` keeps `aliceRequest` and
`bobRequest` as *instance fields*. A lambda that reads an instance field captures
`this` — so serializing the lambda serializes the whole `InitialInvite` object,
fields and all. The callflow object *is* the state container. That is why
`Callflow` implements `Serializable` and why every callflow declares a
`serialVersionUID`.

The practical consequences:

- **Everything reachable from the lambda is written to session memory on every
  hop.** Capture the two messages you need, not the configuration tree they came
  from. Read settings into locals at the top of `process` and keep only the
  fields you use.
- **Anything captured must be serializable.** A non-serializable handle — a
  database connection, an executor, a logger you built yourself — fails at
  replication time, on a different node, under load. Not in your unit test.
- **Do not hold a callflow reference longer than the callflow.** `InitialInvite`
  parks itself in a message attribute so the listener can see which callflow ran,
  then immediately removes it — *"Remove the callflow so it's not serialized."*

## 6. expectRequest: messages that may never come

`sendRequest` and `sendResponse` cover messages you asked for. Some messages
arrive because the other side decided — a CANCEL, a BYE mid-transfer. For those
there is `expectRequest`:

```java
Expectation expectRequest(SipSession session, String method, Callback<SipServletRequest> lambda)
```

It registers your lambda in the same `REQUEST_CALLBACK_<METHOD>` slot that
`sendResponse` uses for ACK, and hands back an `Expectation` you can `clear()`
when the situation no longer applies. As the source puts it, *"you don't have to
write a complete CANCEL Callflow class. How convenient!"*

`BlindTransfer` is the reason this exists. Bob asks to transfer Alice to Carol.
Between that REFER and Carol answering, either Alice or Bob may hang up, and each
needs different treatment:

```java
// in the event the transferee hangs up before the transfer completes
Expectation aliceExpectation = expectRequest(transfereeRequest.getSession(), BYE, (bye) -> {
    sendResponse(bye.createResponse(200));
    if (targetRequest.getSession().isValid()
            && targetRequest.getSession().getState() == SipSession.State.EARLY) {
        sendRequest(targetRequest.createCancel());
    }
    transferListener.transferAbandoned(bye);
});

// In case transferor (bob) hangs up.
Expectation bobExpectation = expectRequest(transferorRequest.getSession(), BYE, (bye) -> {
    sendResponse(bye.createResponse(200));
});
```

Then, once Carol answers, Alice's hang-up stops being an abandonment and becomes
an ordinary end of call, so the expectation is withdrawn:

```java
// Alice will no longer hangup, expect a BYE from Bob
aliceExpectation.clear();
```

Follow `aliceExpectation` through that file and you have the whole model in one
variable. It is created before the INVITE to Carol goes out. It is serialized
with the callflow. It may be invoked seconds later on a different node. And it is
cleared from inside a lambda nested two levels deep in a different transaction.
None of that required a state machine, a correlation table, or a single
`getAttribute`.

Read [`BlindTransfer.process`](libs/framework/src/main/java/org/vorpal/blade/framework/v2/transfer/BlindTransfer.java)
end to end when you have twenty minutes. It handles a REFER, a 202, a pending
NOTIFY, an INVITE to the target, a re-INVITE to the transferee, two ACKs, a
terminating NOTIFY, and three distinct failure paths — and the PlantUML sequence
diagrams at the top of the file are drawn from the same code. It is the most
instructive file in the repository.

## 7. Packaging a service

The mechanics, briefly. A service is one WAR on the OCCAS engine tier: a servlet,
its callflows, a config class, and two descriptors.

**The servlet.** Extend `v3.AsyncSipServlet` and implement its three abstract
methods — `servletCreated`, `servletDestroyed`, and `chooseCallflow`, which picks
the callflow per inbound request (return `null` to let the container absorb the
message). Or extend `v3.B2buaServlet`, which writes `chooseCallflow` for you and
gives you six lifecycle callbacks instead. Four annotations are required:
`@WebListener`, `@SipApplication(distributable = true)`,
`@SipServlet(loadOnStartup = 1)`, `@SipListener`. `distributable` is what makes
§5 work.

Every `B2buaServlet` callback hands you **the message about to be sent**, so
changing it changes what goes on the wire:

| Callback | The message you receive | Emitted at |
| --- | --- | --- |
| `callStarted` | the outbound INVITE to the callee | `InitialInvite.java:125` |
| `callAnswered` | the 2xx **to the caller**, copied from the callee's | `InitialInvite.java:184` |
| `callConnected` | the outbound **ACK** to the callee | `InitialInvite.java:226` |
| `callDeclined` | the failure response to the caller (4xx, 5xx, 6xx) | `InitialInvite.java:199` |
| `callAbandoned` | the outbound **CANCEL** to the callee | `Terminate.java:130` |
| `callCompleted` | the outbound **BYE** to the other leg | `Terminate.java:146` |

Two of those mislead if read casually. `callConnected` is the ACK, not the
answer. `callCompleted` and `callAbandoned` give you the request you are
*sending onward*, not the BYE or CANCEL that arrived. To stop a call instead of
passing it on, call `doNotProcess(outboundRequest, 403, "Forbidden")` from
`callStarted`.

**Configuration.** A POJO extending `Configuration`, managed by a
`SettingsManager`. Put `@SchemaAbout` on the class — it is the app's identity in
the Admin Portal — and `@JsonPropertyDescription` on getters, never fields, since
that text becomes the operator's help in the Configurator. Read the config once
at the top of a callflow, not repeatedly: an operator can republish mid-call. See
the [config guide](libs/framework/src/main/java/org/vorpal/blade/framework/v2/config/README.md)
for file locations and the domain/cluster/server merge.

**Naming.** `<finalName>` in `pom.xml` must equal `<wls:context-root>` in
`weblogic.xml`, and both must be one flat segment. That name is what the
container reports to the application router, so it is what FSMAR's `next`
resolves against. Get it wrong and the service deploys, answers nothing, and
gives you no error to search for. Module directory names must also be unique
across `libs/`, `admin/`, `services/`, `test/`, `apps/` and `proto/`; `build.sh`
fails the build if they collide.

**Descriptors.** `weblogic.xml` needs the `blade-shared` `library-ref` — that is
where every third-party JAR comes from at runtime. Do not add third-party
dependencies to a service pom; the parent's war plugin strips everything but the
framework JAR from `WEB-INF/lib`.

**Build and deploy.** New apps start in `proto/`. Add a profile to the root
`pom.xml` (`!skip.<name>` plus the `<module>` line) and the bare name to
`build-profiles/full.conf`, then `./build.sh full`. Services deploy to the engine
cluster with `./deploy.sh <env> services`; `proto/` modules are deployed by hand.
See [DEPLOYMENT.md](DEPLOYMENT.md).

**Watching it run.** `sipLogger` is a protected static on both the servlet and
`Callflow`; pass the message first — `sipLogger.info(request, "…")` — and it
stamps session hashes so you can follow one call through a busy file. It draws
the sequence-diagram arrows you see in `<domain>/servers/<server>/logs/vorpal/`.

---

## When something goes wrong

Two tools, and most people reach for the second one far too late.

**Locally, run the callflow in a test.** If the question is "what did my code
build," you do not need a deployment. Install the
[detached SIP objects](libs/framework/src/main/java/org/vorpal/blade/framework/sip/README.md),
call `process(request)`, hand it a response, and assert. Seconds, not a deploy cycle.

**On a server, record the call.** The [Trace viewer](admin/callflow/README.md) is
the fastest way to answer "which app in the chain misbehaved, and on which line."
It records every message every application sent and received, stitches them into
one ladder diagram using the `X-Vorpal-Session` id, and pins each arrow to the
source line that emitted it — read live from the deployed code, so it cannot drift
from what is running.

It is off until armed, and arming is per-rule rather than global, so you record
your own test call and nothing else:

1. Open **Trace** on the Admin Portal.
2. Arm a rule that matches your call — a [`Selector`](libs/framework/src/main/java/org/vorpal/blade/framework/v2/config/README.md)
   on any header, so "calls from my desk phone" is a match on `From`. Arming fans
   out to every application in the domain, so the rule catches the call wherever
   it lands.
3. Place the call.
4. Read the recording, then disarm.

A rule carries a capture cap (`maxCaptures`, default 5) precisely so a match on a
busy header cannot quietly record thousands of calls if you forget step 4. Nothing
is persisted — a trace session is arm, reproduce, disarm — and a disarmed call
costs one boolean read per event, which is why this can ship enabled in production.

From code, `Callflow.enableTrace(appSession)` arms one call directly, and
`Callflow.getTrace(appSession)` returns that call's steps in-process.

## 8. House rules

- **No singletons.** Every node runs independently at 1000+ CPS. Shared state
  belongs in the session or a real external store.
- **Keep WARs skinny.** Only the framework JAR in `WEB-INF/lib`.
- **Never remove the `library-ref`**, and never change a shipped
  `context-root` — it is the config file name, the JMX name and the FSMAR target
  at once.
- **Prefer `switch` over chained `String.equals()`.**
- **Markdown Javadoc** (`///`), not legacy HTML `/** */`.
- **Test with JUnit.** A `SipServlet` subclass cannot be instantiated outside the
  container — but a callflow can. Install the
  [detached SIP objects](libs/framework/src/main/java/org/vorpal/blade/framework/sip/README.md)
  and you can run a whole callflow, deliver it a response and assert on what it
  built, in milliseconds. Keep decisions in callflows and plain classes, keep the
  servlet thin, and nearly everything is testable on your laptop.

## Where to go next

- [`BlindTransfer`](libs/framework/src/main/java/org/vorpal/blade/framework/v2/transfer/BlindTransfer.java) — the deep end, with sequence diagrams
- [`InitialInvite`](libs/framework/src/main/java/org/vorpal/blade/framework/v2/b2bua/InitialInvite.java) — the B2BUA callflow in full
- [test/test-b2bua](test/test-b2bua/README.md) — the template most people copy
- [detached SIP objects](libs/framework/src/main/java/org/vorpal/blade/framework/sip/README.md) — run a callflow in a JUnit test
- [b2bua guide](libs/framework/src/main/java/org/vorpal/blade/framework/v2/b2bua/README.md) · [callflow guide](libs/framework/src/main/java/org/vorpal/blade/framework/v2/callflow/README.md) · [config guide](libs/framework/src/main/java/org/vorpal/blade/framework/v2/config/README.md)
- [v3 API](libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — tracing, passthru, config-first routing
- [DEPLOYMENT.md](DEPLOYMENT.md) · [BLADE](README.md)
