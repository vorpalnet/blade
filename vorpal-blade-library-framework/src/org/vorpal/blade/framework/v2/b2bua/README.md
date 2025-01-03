# Vorpal:BLADE Framework B2bUA

See Javadocs: [org.vorpal.blade.framework.b2bua](https://vorpalnet.github.io/vorpal-blade-library-framework/index.html?org/vorpal/blade/framework/b2bua/package-summary.html)

The 'b2bua' utility provides pre-built callflows for creating B2BUA applications.

Take a look at the [test-b2bua](https://github.com/vorpalnet/blade/tree/main/vorpal-blade-test-b2bua) code for a complete example.

Consider this example:

```


@WebListener
@javax.servlet.sip.annotation.SipApplication(distributable = true)
@javax.servlet.sip.annotation.SipServlet(loadOnStartup = 1)
@javax.servlet.sip.annotation.SipListener
public class SampleB2buaServlet extends B2buaServlet {
	private static SettingsManager<SampleConfig> settingsManager;

	/*
	 * This is invoked when the servlet starts up.
	 */
	@Override
	public void servletCreated(SipServletContextEvent event) { 
	}
	
	/*
	 * This is invoked when the servlet shuts down.
	 */
	@Override
	public void servletDestroyed(SipServletContextEvent event) {
	}

	/*
	 * This is the outbound INVITE request to Bob, it can be modified.
	 */
	@Override
	public void callStarted(SipServletRequest request) {
		try {
			request.setRequestURI(sipFactory.createURI("sip:10.11.12.13"));
		} catch (Exception e) {
			sipLogger.severe(request, e.getMessage());
			sipLogger.logStackTrace(e);
		}
	}

	/*
	 * This is the final response to Alice, it can be modified.
	 */
	@Override
	public void callAnswered(SipServletResponse response) throws ServletException, IOException {
	}

	/*
	 * This is the ACK sent to Alice.
	 */
	@Override
	public void callConnected(SipServletRequest request) throws ServletException, IOException {
	}

	/*
	 * This should be a BYE request from either Alice or Bob.
	 */
	@Override
	public void callCompleted(SipServletRequest request) throws ServletException, IOException {
	}

	/*
	 * This should be the error code from Bob, the destination.
	 */
	@Override
	public void callDeclined(SipServletResponse response) throws ServletException, IOException {
	}

	/*
	 * This should be a CANCEL from Alice.
	 */
	@Override
	public void callAbandoned(SipServletRequest request) throws ServletException, IOException {
	}

}

```

To use the Vorpal:BLADE B2BUA framework, you simply need to extend the class B2buaServlet and
override this methods:

* callStarted
* callAnswered
* callConnected
* callDeclined
* callAbandoned
* callCompleted

The B2buaServlet is derived from AsyncSipServlet which has these methods:
* servletCreated
* servletDestroyed

For each method, the BLADE framework with pass your code the outgoing SIP message giving you a chance to modify
it before it is sent.

Typically, all you need to do is modify the request URI of the SipServletRequest object passed into the callStarted() method
to route the call to the outgoing proxy gateway.

It's that simple!