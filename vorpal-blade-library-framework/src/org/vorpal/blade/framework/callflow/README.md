# Vorpal:BLADE Framework Callflow

See Javadocs: [org.vorpal.blade.framework.callflow](https://vorpalnet.github.io/blade/vorpal-blade-library-framework/org/vorpal/blade/framework/callflow/Callflow.html)

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

Instead, the Vorpal:BLADE APIs utilize lambda expressions to simplify things.

For instance, here's an example:

```
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
It take a SipServletRequest and returns a SipServetResponse (bobResponse). When the SipServletResponse is received,
the you have some code to create the return response and you call the matching "sendResponse()" method.
It accepts an ACK (aliceAck). At that point, you can create the outgoing ACK for bob and send it via sendRequest() method again.
Now you're done with the callflow.

Wait, how did that work?

The methods "sendRequest()" and "sendResponse()" utilize lambda expressions. Under the covers, they're inserting SipSession
state variables, calling ".send()" and awaiting the response from the container. Once a response is received, your
code within the lambda expression is invoked. Pretty neat, huh? It sure makes things more readable.

Wait, there's something funny going on... What's up with the 180 Ringing and 200 Ok messages? It doesn't seem like you're
checking for them.

That's right... For the lambda expression for "sendRequest(bobRequest, (bobResponse) -> { //lambda expression });"
will get invoked twice: Once for 180 Ringing and again for 200 OK. That's actually cool, because we're waiting for return ACK
as seen in "sendResponse(aliceResponse, (aliceAck) -> { //lambda expression });" When that ACK comes in, you won't get any more
SipServletResponse messages.

Finally, you'll notice the final method "sendRequest(bobAck);" doesn't have a lambda expression associated with it.
That makes sense because none is possible. You can use "sendRequest()" without an associated lambda expression if
you just don't care and want to let the container eat it message. For instance, sending a BYE request will always return a 200 OK.
There's no need to define a lambda expression that has no logic in it.

Now you have the basic understanding of how the BLADE framework works. Everything else is simply implementations of specific
callflows and variations on this major theme.



