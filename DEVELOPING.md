# BLADE Developer's Guide

How to write a BLADE application.

This guide is for two readers: the developer who knows Java but not SIP, and the
network engineer who knows SIP but not Java. Each chapter introduces one part of
the framework and links to a README that goes deeper. Building and deploying are
covered separately in [BUILDING.md](BUILDING.md) and [DEPLOYING.md](DEPLOYING.md).

## Contents

1. [The Problem](#1-the-problem)
2. [OCCAS and the SIP Servlet API](#2-occas-and-the-sip-servlet-api)
3. [Callflow](#3-callflow)
4. [AsyncSipServlet](#4-asyncsipservlet)
5. [SettingsManager](#5-settingsmanager)
6. [Timers](#6-timers)
7. [Expectations](#7-expectations)
8. [Logging](#8-logging)
9. [Testing](#9-testing)
10. [Events](#10-events)

---

## 1. The Problem

A phone call is a conversation: an INVITE, some ringing, an answer, an
acknowledgement, and eventually a hang-up. The standard servlet model chops that
conversation into separate handler methods — one for requests, one for
responses, one for acknowledgements — each called at a different moment, none
aware of what the others saw. To connect two events in the same call, you store
state in one handler and retrieve it in another. The state variables multiply,
the switch statements grow, and the logic of a single call ends up scattered
across the whole class. Reading it is like reading a Choose Your Own Adventure
book: to follow one story, you keep turning to page 57.

BLADE turns the book into a collection of poems. Each conversation is written
once, top to bottom, in a single method. The events still arrive one at a time,
minutes apart, perhaps on different machines — but the code reads in the order
the conversation happens.

> **PLACEHOLDER:** code snippet contrasting the traditional style with the
> BLADE style. *(Jeff to supply.)*

## 2. OCCAS and the SIP Servlet API

BLADE applications run on OCCAS — Oracle Communications Converged Application
Server, a WebLogic application server with a built-in SIP container. An
application is a WAR file; it deploys, clusters, and fails over like any
WebLogic application, and it may serve SIP and HTTP from the same WAR.

The SIP Servlet API (JSR 359) presents SIP to Java. Each SIP message is an
object — a `SipServletRequest` or a `SipServletResponse` — with methods to read
and set headers and content. The container does the plumbing: parsing,
transactions, retransmission, dialog bookkeeping, and the system headers
(`Via`, `Call-ID`, `CSeq`, and the rest) that applications must not touch.

For the reader new to SIP: a request names an action (INVITE starts a call, BYE
ends one, CANCEL retracts an unanswered INVITE); a response answers a request
with a status code. Responses below 200 are *provisional* — `180 Ringing`
reports progress. Responses of 200 and above are *final* — `200 OK` accepts,
`486 Busy Here` declines. A caller confirms a final answer to an INVITE with an
ACK. A basic call is INVITE, 180, 200, ACK — conversation — BYE, 200.

> **PLACEHOLDER:** sequence diagram, call setup. *(Jeff to supply.)*

> **PLACEHOLDER:** sequence diagram, call teardown. *(Jeff to supply.)*

Import BLADE classes from `org.vorpal.blade.framework.v3`. Your IDE will also
offer older packages whose names it strikes through as deprecated; skip them.

## 3. Callflow

A callflow is the poem: one SIP conversation, written as one method. Extend
`Callflow` and implement `process()`, which receives the request that starts
the conversation:

```java
public class Connect extends Callflow {

    @Override
    public void process(SipServletRequest request) throws ServletException, IOException {
        // the conversation, top to bottom
    }
}
```

Inside `process()`, three methods carry the conversation forward. Each takes a
message to send and, optionally, a *lambda* — a block of code to run when the
reply arrives. If lambdas are new to you, read

```java
sendRequest(bobRequest, (bobResponse) -> {
    // ...
});
```

as: "Send `bobRequest`. When a response arrives, name it `bobResponse` and do
this." The method returns immediately; the block runs later, when the far end
answers.

**`sendRequest(request, lambda)`** sends a request; the lambda receives each
response. **`sendResponse(response, lambda)`** sends a response; the lambda
receives the acknowledgement (the ACK or PRACK) — for after you answer, what
you wait for is not a response but a request.
**`sendAcknowledgement(ackOrPrack, response)`** forwards an acknowledgement
received on one call dialog to the other, whichever kind it is.

Here is a complete two-party bridge — Alice calls, we invite Bob:

```java
sendRequest(bobRequest, (bobResponse) -> {
    SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
    copyContentAndHeaders(bobResponse, aliceResponse);
    sendResponse(aliceResponse, (aliceAck) -> {
        sendAcknowledgement(aliceAck, bobResponse);
    });
});
```

Read it as a sentence: *invite Bob; whatever he says, say the same to Alice;
when Alice acknowledges, acknowledge Bob.*

### One request, several responses

An INVITE draws more than one response: first `180 Ringing`, then the answer.
The `sendRequest` lambda runs for **every** response to its request — once for
the 180, again for the 200 — and stops after a final response arrives. Branch
on the kind of response with the helpers `provisional()` (1xx), `successful()`
(2xx), `redirection()` (3xx), and `failure()` (4xx and above):

```java
sendRequest(bobRequest, (bobResponse) -> {
    if (provisional(bobResponse)) {
        // Bob is ringing
    } else if (successful(bobResponse)) {
        // Bob answered
    } else if (failure(bobResponse)) {
        // Bob declined
    }
});
```

Failures of your own arrive the same way: if a send fails locally, your lambda
receives a `500` response rather than an exception, so one `failure()` branch
covers both a remote decline and a local error.

The one-argument forms, `sendRequest(request)` and `sendResponse(response)`,
take no lambda; whatever comes back is absorbed. Use them when there is nothing
to decide — an ACK, or a BYE whose `200 OK` you do not care about. Do not write
an empty lambda for a reply you mean to ignore; the absence of a lambda is the
statement.

### The cluster keeps your place

A callflow survives failover. Send an INVITE from one engine node, and the
response may run your lambda on another; the conversation continues as if
nothing happened. BLADE preserves the callflow object — its fields and the
variables its lambdas use — across the cluster. In return, two obligations:

- Every field and captured variable must be serializable. SIP messages,
  strings, and numbers are; a database connection or an open socket is not.
- Keep them small. Capture the two messages you need, not the object tree they
  came from.

*More:* [v3 framework README](libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) ·
[callflow README](libs/framework/src/main/java/org/vorpal/blade/framework/v2/callflow/README.md)

## 4. AsyncSipServlet

The servlet is the front door: the container hands it every message, and it
decides which callflow runs. Extend `AsyncSipServlet` and implement three
methods — `servletCreated` and `servletDestroyed`, called once each at
deployment and undeployment, and `chooseCallflow`, called for each new
conversation:

```java
@WebListener
@SipApplication(distributable = true)
@SipServlet(loadOnStartup = 1)
@SipListener
public class ExampleServlet extends AsyncSipServlet {
    private static final long serialVersionUID = 1L;

    public static ExampleSettingsManager settingsManager;

    @Override
    public void servletCreated(SipServletContextEvent event) throws ServletException, IOException {
        settingsManager = new ExampleSettingsManager(event);
    }

    @Override
    public void servletDestroyed(SipServletContextEvent event) throws ServletException, IOException {
        settingsManager.unregister();
    }

    @Override
    protected Callflow chooseCallflow(SipServletRequest request) throws ServletException, IOException {
        if (request.isInitial() && request.getMethod().equals("INVITE")) {
            return new Connect();
        }
        return null;
    }
}
```

`chooseCallflow` examines the request and returns the callflow to run, or
`null` to let the framework respond on its own. The four annotations are
required; `distributable = true` is what turns on failover.

For the common case — receive a call, place a call, bridge them — extend
`B2buaServlet` instead. It supplies `chooseCallflow` and runs the bridge for
you; you override any of six lifecycle callbacks: `callStarted`,
`callAnswered`, `callConnected`, `callDeclined`, `callAbandoned`, and
`callCompleted`. Each hands you the message about to be sent, so changing the
message changes what goes on the wire. To reject a call instead of connecting
it, call `doNotProcess(outboundRequest, 403, "Forbidden")` from `callStarted`.

*More:* [b2bua README](libs/framework/src/main/java/org/vorpal/blade/framework/v2/b2bua/README.md) ·
[test/test-b2bua](test/test-b2bua/README.md), the template most services copy

## 5. SettingsManager

A configuration class becomes a configuration file. Write a plain Java object
for your settings; BLADE turns it into a JSON config file, a schema that
validates it, a web form in the Admin Portal's Configurator, and a live reload
whenever an operator publishes a change. You write no parsing code and no admin
screens.

```java
@SchemaAbout("Connects callers to the example service.")
public class ExampleConfig extends Configuration {
    private int maxCalls;

    @JsonPropertyDescription("Maximum simultaneous calls")
    public int getMaxCalls() { return maxCalls; }
    public void setMaxCalls(int maxCalls) { this.maxCalls = maxCalls; }
}
```

Extending `Configuration` adds the sections every application shares — logging
levels, session parameters — so operators find them in every config file in the
same place.

```java
public class ExampleSettingsManager extends SettingsManager<ExampleConfig> {
    public ExampleSettingsManager(SipServletContextEvent event) throws ServletException, IOException {
        super(event);
    }
}
```

Create the manager in `servletCreated`, as in the previous chapter. Wherever
configuration is needed, `settingsManager.getCurrent()` returns the live
config. Read it once at the top of a callflow and work from what you read — an
operator can publish a new configuration in the middle of a call.

Two annotations feed the Configurator. `@SchemaAbout` on the class describes
the application; `@JsonPropertyDescription` on each getter — on the getter, not
the field — becomes the operator's help text for that form field.

Three optional refinements: override `sample()` to supply the configuration a
fresh install starts with; override `onRefresh()` to run code on every load and
reload; pass a second constructor argument, a name, to manage additional
config files beyond the application's own.

*More:* [config README](libs/framework/src/main/java/org/vorpal/blade/framework/v2/config/README.md) —
file locations and how domain, cluster, and server files merge

## 6. Timers

`sendRequest` and `sendResponse` wait for messages. Some situations are defined
by the absence of one: nobody answered within fifteen seconds. For those,
`startTimer`:

```java
String timerId = startTimer(request.getApplicationSession(), 15000, false, (timer) -> {
    sendRequest(bobRequest.createCancel());
});

sendRequest(bobRequest, (bobResponse) -> {
    if (!provisional(bobResponse)) {
        stopTimer(bobResponse.getApplicationSession(), timerId);
    }
    // ...
});
```

Send the INVITE and start a fifteen-second timer. If a final response arrives
first, stop the timer; if the timer fires first, CANCEL the call. `startTimer`
returns an id — keep it in a field when the code that cancels is not the code
that started. A second form takes an additional period and repeats until
stopped; the third argument, `false` above, says whether the timer should
survive a server restart (almost never).

Two habits keep timers honest. First, a timer races the thing it guards, and
the race can be decided but not prevented — a CANCELed INVITE still delivers a
final response (a 487) to your lambda moments later, so expect it. Second, a
timer always fires late relative to something: begin every timer lambda by
checking that its purpose still applies, and do nothing if it does not.

If your timer is really a no-answer timeout across several destinations, the
callflow you are about to write already exists: `sendRequestsInSerial` tries a
list of destinations one at a time, and `sendRequestsInParallel` races them and
takes the first answer.

## 7. Expectations

The messages so far were invited: a response to your request, an ACK to your
response. Some messages arrive because the far end decided — a BYE or CANCEL in
the middle of something else. Declare them with `expectRequest`:

```java
Expectation aliceHangsUp = expectRequest(aliceRequest.getSession(), BYE, (bye) -> {
    sendResponse(bye.createResponse(200));
    // Alice gave up before the transfer finished
});
```

The lambda runs if and when that request arrives on that session. The returned
`Expectation` is the off switch: when events overtake the concern — the
transfer completed, so a BYE is now an ordinary hang-up — withdraw it with
`aliceHangsUp.clear()`.

Expectations are what let a callflow state its exceptional paths beside its
normal one. The [transfer service](services/transfer/README.md) is the worked
example: one expectation covers "the caller hung up mid-transfer," another
covers "the transferring party hung up," and each is cleared the moment it no
longer applies.

## 8. Logging

Every servlet and callflow has `sipLogger`. Pass the message as the first
argument and the log entry is stamped with the call it belongs to, so one call
can be followed through a busy log:

```java
sipLogger.info(request, "transferring to " + target);
```

The usual levels apply — `severe`, `warning`, `info`, `fine`, `finer` — and the
level is set per application, live, from the Admin Portal. Each application
writes its own log under `<domain>/servers/<server>/logs/vorpal/`, where SIP
messages appear as sequence-diagram arrows: what arrived, what was sent, and in
which direction.

When the question is "which application in the chain misbehaved," record the
call instead of reading logs. The [Trace viewer](admin/callflow/README.md) on
the Admin Portal captures every message each BLADE application sent and
received for a matching call and stitches them into a single ladder diagram.
Arm a rule that matches your test call, place the call, read the diagram,
disarm. It is safe to leave deployed in production; a disarmed rule costs
almost nothing.

*More:* [logging README](libs/framework/src/main/java/org/vorpal/blade/framework/v2/logging/README.md)

## 9. Testing

A callflow is a plain Java class, and it runs without OCCAS. The framework
ships *detached* SIP objects — requests, responses, and sessions that exist
without a container — so a JUnit test can construct an INVITE, hand it to
`process()`, deliver a response, and assert on what the callflow sent:
milliseconds per test, no deployment.

Keep decisions in callflows and plain classes; keep the servlet thin. The
servlet itself cannot be instantiated outside the container, so anything worth
testing should live below it — which is where it belongs anyway.

*More:* [detached SIP objects README](libs/framework/src/main/java/org/vorpal/blade/framework/sip/README.md)

## 10. Events

Applications tell the rest of the system what happened by publishing events: a
call answered, a meeting scheduled, a threshold crossed. An event is a
[CloudEvent](https://cloudevents.io/) — a small JSON envelope with a type, a
source, a subject, and data — published to the BLADE event bus, where any
number of applications may subscribe:

```java
EventBus.publish(CloudEvent.create(
        "net.vorpal.example.call.answered",   // type
        "/example",                           // source: this application
        getVorpalSessionId(request),          // subject: the call
        data));                               // your JSON payload
```

Publishing is fire-and-forget and always safe: if the bus is not provisioned,
`publish` quietly does nothing. Subscribers select events by type or subject
without parsing bodies, and more than one application can consume the same
event, each receiving its own copy. The event catalog — itself ordinary BLADE
configuration — names the event types and decides where each is delivered.

*More:* [events service README](services/events/README.md)

---

*Packaging, building, and deploying a finished service — WAR layout, naming
rules, dev/prod modes — are covered in [BUILDING.md](BUILDING.md) and [DEPLOYING.md](DEPLOYING.md).*
