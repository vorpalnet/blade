# Vorpal:BLADE Framework Logging

See Javadocs: [org.vorpal.blade.framework.logging](https://vorpalnet.github.io/vorpal-blade-library-framework/index.html?org/vorpal/blade/framework/logging/package-summary.html)

The 'logging' package offers some nifty logging utilities for understanding SIP callflows.

Here's an example of how to use it.

If you're using the AsyncSipServlet or one of its extended classes (B2buaServet or ProxyServlet), you can simply use the
protected data member 'sipLogger'.

```
public abstract class AsyncSipServlet extends SipServlet
		implements SipServletListener, ServletContextListener, TimerListener {
	protected static Logger sipLogger;
...

```

If you need to create the sipLogger from scratch, you can do it by:

```
Logger sipLogger = org.vorpal.blade.framework.logging.LogManager.getLogger( servletContextEvent );

```

Or more generically:

```
Logger sipLogger = org.vorpal.blade.framework.logging.LogManager.getLogger( appName );

```

This will create a log file for your ServletContect name or 'appName' in the directory:

<domain>/servers/<serverName>/logs/vorpal/<appName>.log

You can use the following methods to create logging statements:

```
	public void fine(SipServletMessage message, String comments);
	public void finer(SipServletMessage message, String comments);
	public void finest(SipServletMessage message, String comments);
	public void info(SipServletMessage message, String comments);
	public void severe(SipServletMessage message, String comments);
	public void warning(SipServletMessage message, String comments);
```

This will create hash codes for the SipApplicationSession and SipSession of the message, allowing you
to track individual transactions through the logs.

You can control the logging levels through the OCCAS admin console by selecting the server and then the logging level.
The BLADE logging utility will automatically adjust.







