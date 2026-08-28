# Build a B2BUA in BLADE

A hands-on lesson. By the end you will have a working back-to-back user agent — an app
that sits between two phones and bridges a call from a caller ("Alice") to a callee
("Bob") — deployed to your OCCAS domain, with a real call flowing through it and the whole
SIP exchange traced in your log.

You will write four small Java classes and touch three config files. No prior BLADE
experience is assumed. You should be comfortable with Java and know what a SIP INVITE is.

We will build the `test-b2bua` app itself, step by step. If you would rather build your
own, swap `test-b2bua` for your app's name wherever it appears — nothing else changes.

**Time:** about 30 minutes.

## Before you begin

You need three things in place:

1. **An OCCAS domain you can deploy to.** If you don't have one, work through
   [INSTALLING.md](../../INSTALLING.md) first, then come back.
2. **The BLADE repo, built once.** From the repo root: `./build.sh`. This installs the
   framework your app compiles against.
3. **`test-uac` and `test-uas` deployed** to the same domain. These are your Alice and
   Bob: one places calls, the other answers them. You'll deploy your own app the same way
   in Step 6, so you can deploy these now or circle back.

Create the module directory and change into it:

```bash
mkdir -p test/test-b2bua/src/main/java/org/vorpal/blade/test/b2bua
cd test/test-b2bua
```

Copy the three descriptors — `pom.xml`, `src/main/webapp/WEB-INF/web.xml`, and
`src/main/webapp/WEB-INF/weblogic.xml` — from any existing test module; they are boilerplate
every BLADE app shares. Only one line is yours to set, the context root in `weblogic.xml`:

```xml
<wls:context-root>test-b2bua</wls:context-root>
```

That name is how the app deploys and how other apps route to it. One line below it,
`weblogic.xml` references the `blade-shared` library — that's where BLADE keeps its
third-party jars, so your WAR stays skinny and carries only the framework. Leave it as is.

Now the interesting part: the four Java classes.

## Step 1 — A servlet that dispatches

Every BLADE app starts with one servlet. Its whole job is to look at each inbound SIP
request and hand it to the right *callflow* — a small class that handles one exchange.

Create `src/main/java/org/vorpal/blade/test/b2bua/TestB2buaSipServlet.java`:

```java
package org.vorpal.blade.test.b2bua;

import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.Callflow;
import org.vorpal.blade.framework.v3.AsyncSipServlet;

@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class TestB2buaSipServlet extends AsyncSipServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected Callflow chooseCallflow(SipServletRequest inboundRequest) {
        switch (inboundRequest.getMethod()) {
        case Callflow.INVITE:  return new TestB2buaInvite();
        case Callflow.CANCEL:  return new TestB2buaCancel();
        default:               return new TestB2buaPassthru();
        }
    }
}
```

Two things to notice. The class extends `AsyncSipServlet` — the framework's base servlet,
which does the SIP plumbing and calls your `chooseCallflow` for each new request. And
`chooseCallflow` is the entire routing table: an INVITE sets up a call, a CANCEL tears a
setup down, and everything else (BYE, INFO, OPTIONS) is a straight passthru. You'll write
those three callflows next.

The `@SipApplication(distributable = true)` annotation is the one that earns its keep: it
replicates SIP sessions across the cluster, so a call survives a node failing mid-way.

Your editor will flag `TestB2buaInvite`, `TestB2buaCancel`, and `TestB2buaPassthru` as
missing. That's expected — write them now.

## Step 2 — The INVITE callflow (the heart)

This is the class that bridges the call, and it's where BLADE's whole idea lives. Create
`TestB2buaInvite.java`:

```java
package org.vorpal.blade.test.b2bua;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v3.Callflow;

public class TestB2buaInvite extends Callflow {
    private static final long serialVersionUID = 1L;

    @Override
    public void process(SipServletRequest aliceRequest) throws ServletException, IOException {

        SipServletRequest bobRequest = createRequest(aliceRequest);
        sendRequest(bobRequest, (bobResponse) -> {

            SipServletResponse aliceResponse = createResponse(aliceRequest, bobResponse);
            sendResponse(aliceResponse, (aliceAck) -> {

                SipServletRequest ack = createAcknowledgement(bobResponse, aliceAck);
                sendAcknowledgement(ack, bobResponse);
            });
        });
    }
}
```

Read it top to bottom — it runs in the order the messages arrive.

- `createRequest(aliceRequest)` clones Alice's INVITE into one aimed at Bob, and links the
  two call dialogs.
- `sendRequest(bobRequest, …)` sends it and hands your lambda Bob's response *when it
  arrives*.
- `createResponse(aliceRequest, bobResponse)` builds Alice's answer from Bob's.
- `sendResponse(aliceResponse, …)` sends it and hands your lambda Alice's ACK.
- `createAcknowledgement` and `sendAcknowledgement` pass the ACK on to Bob. Done.

Here is the thing worth pausing on. In classic SIP servlet code, that exchange is scattered
across a `doInvite`, a `doResponse`, and a `doAck`, with state variables to remember where
each dialog stands. Here it is one method you read from top to bottom. Between each step the
callflow's state serializes into the SIP session and the method returns — so when Bob's
response lands, the framework wakes your lambda back up on whatever cluster node the
container routed it to. That's why the call survives failover.

One subtlety you don't have to handle, but should know: the `sendRequest` lambda fires
**more than once** — once for the 180 Ringing, again for the 200 OK. You never test which;
you just build whichever response back to Alice each time. When her ACK arrives through
`sendResponse`, the exchange is complete.

## Step 3 — CANCEL, and everything else

Two more callflows, both short. `TestB2buaCancel.java` forwards a cancellation to Bob:

```java
package org.vorpal.blade.test.b2bua;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.Callflow;

public class TestB2buaCancel extends Callflow {
    private static final long serialVersionUID = 1L;

    @Override
    public void process(SipServletRequest aliceCancel) throws ServletException, IOException {
        SipServletRequest bobCancel = createCancel(aliceCancel);
        sendRequest(bobCancel);
    }
}
```

Note it uses `createCancel`, not `createRequest` — a CANCEL has to be derived from the
INVITE it cancels, not built fresh. There's no response lambda because the container answers
a CANCEL on its own.

`TestB2buaPassthru.java` handles every other method — BYE, INFO, OPTIONS — by forwarding the
request and returning the response:

```java
package org.vorpal.blade.test.b2bua;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v3.Callflow;

public class TestB2buaPassthru extends Callflow {
    private static final long serialVersionUID = 1L;

    @Override
    public void process(SipServletRequest aliceRequest) throws ServletException, IOException {
        SipServletRequest bobRequest = createRequest(aliceRequest);
        sendRequest(bobRequest, (bobResponse) -> {
            SipServletResponse aliceResponse = createResponse(aliceRequest, bobResponse);
            sendResponse(aliceResponse);
        });
    }
}
```

A BYE runs through here: it goes to Bob, his 200 OK comes back to Alice, no ACK expected.

## Step 4 — Configuration

BLADE gives every app a config file, editable live in the Configurator. You describe its
shape with a plain class. Create `TestB2buaConfiguration.java`:

```java
package org.vorpal.blade.test.b2bua;

import java.io.Serializable;
import org.vorpal.blade.framework.v2.config.Configuration;
import org.vorpal.blade.framework.v2.config.SchemaTitle;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@SchemaTitle("B2BUA Configuration")
public class TestB2buaConfiguration extends Configuration implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonPropertyDescription("Your name")
    public String traveler;

    @JsonPropertyDescription("Your quest")
    public String quest;

    @JsonPropertyDescription("Your favorite color")
    public String color;
}
```

Each field becomes a form control in the Configurator — the form is generated from this
class, never hand-built. The `@JsonPropertyDescription` text becomes the field's help.

Now the manager that loads it. Create `TestB2buaSettingsManager.java`:

```java
package org.vorpal.blade.test.b2bua;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;

import org.vorpal.blade.framework.v3.configuration.SettingsManager;

public class TestB2buaSettingsManager extends SettingsManager<TestB2buaConfiguration> {

    public TestB2buaSettingsManager(SipServletContextEvent event)
            throws ServletException, IOException {
        super(event);
    }

    @Override
    protected TestB2buaConfiguration sample() {
        TestB2buaConfiguration config = new TestB2buaConfiguration();
        config.traveler = "Sir Lancelot of Camelot";
        config.quest    = "To seek the Holy Grail";
        config.color    = "{CLEARTEXT}Blue";
        return config;
    }

    @Override
    protected void refreshed(TestB2buaConfiguration config) throws ServletParseException {
        sipLogger.info("What is your name? " + config.traveler);
        sipLogger.info("What is your quest? " + config.quest);
        sipLogger.info("What is your favorite color? " + config.color);
    }
}
```

A v3 `SettingsManager` requires two methods. `sample()` is the seed written to a fresh
config file the first time you deploy — here, three Monty Python answers. `refreshed()` runs
on that first load and on every later change pushed through the Configurator; it's your
chance to react to new config.

Finally, wire the manager into the servlet. Add a field and a `servletCreated` method to
`TestB2buaSipServlet`:

```java
    public static TestB2buaSettingsManager settingsManager;

    @Override
    public void servletCreated(SipServletContextEvent event) {
        settingsManager = new TestB2buaSettingsManager(event);
    }

    @Override
    public void servletDestroyed(SipServletContextEvent event) {
        try {
            settingsManager.unregister();
        } catch (Exception e) {
            sipLogger.logStackTrace(e);
        }
    }
```

That one constructor call loads the config file, generates the Configurator's form schema,
registers the JMX reload hook, and starts logging. `servletDestroyed` is its mirror image and
is **not optional**: `unregister()` tears the JMX MBean back down when the app undeploys.
Leave it out and every redeploy leaves a stale MBean registered — which is exactly the loop
you'll spend the most time in while developing.

## Step 5 — Build and deploy

From the repo root:

```bash
./build.sh
```

A dev build drops `test-b2bua.war` into the flat `dist/` directory. Deploy it to your
domain (`<env>` is your profile name under `~/.blade/`):

```bash
./deploy.sh <env> test-b2bua.war
```

Watch the server log as it deploys. The first thing you should see is your `refreshed()`
method firing on the initial config load:

```
INFO  What is your name? Sir Lancelot of Camelot
INFO  What is your quest? To seek the Holy Grail
INFO  What is your favorite color? Blue
```

If you see those three lines, your config loaded and the app is live. (See
[DEPLOYING.md](../../DEPLOYING.md) if the deploy step needs setup.)

## Step 6 — Place a call and watch it flow

Use `test-uac` to originate a call toward `test-b2bua`, with `test-uas` as the destination —
that's Alice calling Bob through the app you just built. The exact trigger is in
[test-uac's README](../test-uac/README.md).

Now watch the log. BLADE's logger draws each call as an ASCII sequence diagram — you'll see
the INVITE go out to Bob, the 180 and 200 come back, and the ACK go on, traced across the
three parties:

```
   Alice            test-b2bua           Bob
     |    INVITE        |                 |
     |----------------->|    INVITE       |
     |                  |---------------->|
     |                  |    180 Ringing  |
     |    180 Ringing   |<----------------|
     |<-----------------|    200 OK       |
     |                  |<----------------|
     |    200 OK        |                 |
     |<-----------------|                 |
     |    ACK           |                 |
     |----------------->|    ACK          |
     |                  |---------------->|
```

That diagram is your INVITE callflow, running. Every arrow is a line you wrote — the outbound
`createRequest`, the `createResponse` back to Alice, the `sendAcknowledgement` on to Bob.
The call is up.

## What you built, and where to go next

You wrote a working B2BUA: a servlet that dispatches by method, three callflows that own one
exchange each, and a config file you can edit live. The whole call setup was one method you
read top to bottom, and it runs across a cluster without you touching a thread.

From here:

- **Change the call.** Add a header in `TestB2buaInvite` before `sendRequest`, or reject a
  call by returning early. The callflow is ordinary code.
- **Read [the README](README.md)** for the reference view of every piece you just wrote.
- **When you're ready to stop writing the plumbing,** the framework's `B2buaServlet` packages
  exactly these three callflows, already hardened for the hard cases (a CANCEL racing a 200
  OK, re-INVITEs, PRACK). You extend it and implement six lifecycle callbacks instead. This
  app is what those callbacks sit on top of — which is why you built it by hand first.
