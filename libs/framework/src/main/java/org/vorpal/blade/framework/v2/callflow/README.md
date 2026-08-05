# BLADE Framework Callflow (v2)

Javadocs: package `org.vorpal.blade.framework.v2.callflow` — browse at `/blade/javadoc/framework/` on the Admin Portal

A Callflow is a base class intended to be extended to implement one or more SIP dialogs in a session.

Consider this classic B2BUA callflow:

```
/* 
 * ALICE             BLADE              BOB
 *   |                 |                 | 
 *   | INVITE          |                 | 
 *   |--------------->[ ]                | 
 *   |                [ ] INVITE         | 
 *   |                [ ]--------------->| 
 *   |                 |     180 Ringing | 
 *   |                [ ]<---------------| 
 *   |    180 ringing [ ]                | 
 *   |<---------------[ ]                | 
 *   |                 |    200 OK       | 
 *   |                [ ]<---------------| 
 *   |   200 OK       [ ]                |
 *   |<---------------[ ]                | 
 *   |    ACK          |                 | 
 *   |--------------->[ ]                | 
 *   |                [ ] ACK            | 
 *   |                [ ]--------------->| 
 */
```

Normally, in classic SipServlet design, you would override the "doInvite()", "doResponse()" and "doAck()" methods.
But this gets confusing very fast. Consider "doResponse()". Response to what? Now you have one method to handle
every response. Pretty quickly, you will find yourself implementing state variables to keep
track of all the dialogs. It gets ugly real fast.

Instead, the BLADE APIs use lambda expressions to simplify things.

For instance, here's an example:

```java
public class InitialInvite extends Callflow {
	private SipServletRequest aliceRequest;

	@Override
	public void process(SipServletRequest request) throws ServletException, IOException {

		// Some prep work to create the outgoing SIP INVITE.
			aliceRequest = request;
			SipApplicationSession appSession = aliceRequest.getApplicationSession();
			Address to = aliceRequest.getTo();
			Address from = aliceRequest.getFrom();
			SipServletRequest bobRequest = sipFactory.createRequest(appSession, INVITE, from, to);
			bobRequest.setRoutingDirective(SipApplicationRoutingDirective.CONTINUE, aliceRequest);
			copyContentAndHeaders(aliceRequest, bobRequest);
			bobRequest.setRequestURI(aliceRequest.getRequestURI());
			linkSessions(aliceRequest.getSession(), bobRequest.getSession());

		// This is where the fun begins
			sendRequest(bobRequest, (bobResponse) -> {
					SipServletResponse aliceResponse = aliceRequest.createResponse(bobResponse.getStatus());
					copyContentAndHeaders(bobResponse, aliceResponse);
					sendResponse(aliceResponse, (aliceAck) -> {
							SipServletRequest bobAck = copyContentAndHeaders(aliceAck, bobResponse.createAck());
							sendRequest(bobAck);
					});
			});

	}

}
```

That's a lot to digest, but let's just look at the "where the fun begins" part of the code.

You can see there's a method on Callflow called "sendRequest()". It's meant to replace the SipServletRequest.send() method.
It takes a SipServletRequest and hands your lambda the SipServletResponse (bobResponse). When the response arrives,
you write the code to create the return response and call the matching "sendResponse()" method.
Its lambda accepts an ACK (aliceAck). At that point, you create the outgoing ACK for Bob and send it via the sendRequest() method again.
Now you're done with the callflow.

Wait, how did that work?

The methods "sendRequest()" and "sendResponse()" use lambda expressions. Under the covers, they insert SipSession
state variables, call ".send()" and await the response from the container. Once a response is received, your
code within the lambda expression is invoked. Pretty neat, huh? It sure makes things more readable.

Wait, there's something funny going on... What's up with the 180 Ringing and 200 OK messages? It doesn't seem like you're
checking for them.

That's right... The lambda expression in "sendRequest(bobRequest, (bobResponse) -> { ... });"
gets invoked twice: once for 180 Ringing and again for 200 OK. That's actually cool, because we're waiting for the return ACK
as seen in "sendResponse(aliceResponse, (aliceAck) -> { ... });" When that ACK comes in, you won't get any more
SipServletResponse messages.

Finally, you'll notice the final method call "sendRequest(bobAck);" doesn't have a lambda expression associated with it.
That makes sense because none is possible. You can use "sendRequest()" without a lambda expression if
you just don't care and want to let the container eat the message. For instance, sending a BYE request will always return a 200 OK.
There's no need to define a lambda expression that has no logic in it.

Now you have the basic understanding of how the BLADE framework works. Everything else is simply implementations of specific
callflows and variations on this major theme.

## See also

- [v2 API overview](../README.md)
- [b2bua](../b2bua/README.md) — pre-built callflows so you don't write the above by hand
- [v3 API](../../v3/README.md) — the same model plus call tracing and passthru drop-out
