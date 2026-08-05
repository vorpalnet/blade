# BLADE Framework — v2 API

Javadocs: package `org.vorpal.blade.framework.v2` — browse at `/blade/javadoc/framework/` on the Admin Portal

> **v2 is the stable, maintained API line for existing applications.** New development
> should start with the [v3 API](../v3/README.md), which adds the tracing spine,
> passthru drop-out, and the FSMAR configuration model. The concepts below — callflows,
> lambda expressions, automatic state serialization — carry over to v3 unchanged.

Welcome to the BLADE framework library.

The class at the heart of the v2 package is
`AsyncSipServlet`.
It extends the JSR-359 SipServlet class to provide more functionality.

But wait! Before you extend AsyncSipServlet to create your own classes, consider
`B2buaServlet`
or `ProxyServlet`
for your base application needs.

If you're not building a simple B2BUA or Proxy style application, AsyncSipServlet is a good place to start.

Simply create a custom class and extend it from AsyncSipServlet. Here's an example:

```java
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class MyCustomSipServlet extends AsyncSipServlet {
}
```

Perhaps the most important feature in AsyncSipServlet is the chooseCallflow() method. Override this method to choose which Callflow is right for you.

Example from B2buaServlet:

```java
	@Override
	protected Callflow chooseCallflow(SipServletRequest inboundRequest) throws ServletException, IOException {
		Callflow callflow;

		if (inboundRequest.getMethod().equals("INVITE")) {
			if (inboundRequest.isInitial()) {
				callflow = new InitialInvite(this);
			} else {
				callflow = new Reinvite(this);
			}
		} else if (inboundRequest.getMethod().equals("BYE")) {
			callflow = new Bye(this);
		} else if (inboundRequest.getMethod().equals("CANCEL")) {
			callflow = new Cancel(this);
		} else {
			callflow = new Passthru(this);
		}

		return callflow;
	}
```

Wait, what's a Callflow? [Let's explore that concept next...](callflow/README.md)

## Package guides

- [callflow](callflow/README.md) — the Callflow class and the lambda model
- [b2bua](b2bua/README.md) — pre-built B2BUA callflows
- [config](config/README.md) — JSON configuration files
- [logging](logging/README.md) — SIP-aware logging

## See also

- [v3 API](../v3/README.md) — the current API line
- [Framework library](../../../../../../../../README.md) — module overview, build, packaging
- [BLADE](../../../../../../../../../../README.md) — project home
