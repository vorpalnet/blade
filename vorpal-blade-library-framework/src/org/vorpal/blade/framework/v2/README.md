# Vorpal:BLADE Framework

See Javadocs: [org.vorpal.blade.framework](https://vorpalnet.github.io/blade/vorpal-blade-library-framework/org/vorpal/blade/framework/package-summary.html)

Welcome to the Vorpal:BLADE framework library.

The base package "org.vorpal.blade.framework" contains only one class:
[AsyncSipServlet](https://vorpalnet.github.io/vorpal-blade-library-framework/org/vorpal/blade/framework/AsyncSipServlet.html).

The purpose of this class is to extend the JSR-359 SipServlet class to provide more functionality.

But, wait! Before you start extending AsyncSipServlet to create your own classes, please
consider [B2buaServlet](https://vorpalnet.github.io/blade/vorpal-blade-library-framework/org/vorpal/blade/framework/b2bua/B2buaServlet.html) or [ProxyServlet](https://vorpalnet.github.io/blade/vorpal-blade-library-framework/org/vorpal/blade/framework/proxy/ProxyServlet.html)
for your base application needs.

But if you're not building a simple B2BUA or Proxy style application, AsyncSipServlet is a good place to start.

Simply create a custom class and extend it from AsyncSipServlet. Here's an example:

```
@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class MyCustomSipServlet extends AsyncSipServlet{
}
```

Perhaps the most important feature in AsyncSipServlet is the chooseCallflow() method. Overload this method to choose which Callflow is right for you.

Example from B2buaServlet:

```
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

Wait, what's a Callflow? Let's explore that concept next...


