# BLADE Framework Logging (v2)

Javadocs: package `org.vorpal.blade.framework.v2.logging` — browse at `/blade/javadoc/framework/` on the Admin Portal

The 'logging' package offers SIP-aware logging utilities for understanding callflows.

Here's how to use it.

If you're using the AsyncSipServlet or one of its extended classes (B2buaServlet or ProxyServlet), you can simply use the
protected data member 'sipLogger'.

```java
public abstract class AsyncSipServlet extends SipServlet
		implements SipServletListener, ServletContextListener, TimerListener {
	protected static Logger sipLogger;
...
```

If you need to create the sipLogger from scratch, you can do it by:

```java
Logger sipLogger = LogManager.getLogger( servletContextEvent );
```

Or more generically:

```java
Logger sipLogger = LogManager.getLogger( appName );
```

This creates a log file for your ServletContext name or 'appName' in the directory:

```
<domain>/servers/<serverName>/logs/vorpal/<appName>.log
```

You can use the following methods to create logging statements:

```java
	public void fine(SipServletMessage message, String comments);
	public void finer(SipServletMessage message, String comments);
	public void finest(SipServletMessage message, String comments);
	public void info(SipServletMessage message, String comments);
	public void severe(SipServletMessage message, String comments);
	public void warning(SipServletMessage message, String comments);
```

These create hash codes for the SipApplicationSession and SipSession of the message, allowing you
to track individual transactions through the logs.

You can control the logging levels through the OCCAS admin console by selecting the server and then the logging level.
The BLADE logging utility adjusts automatically.

## See also

- [v2 API overview](../README.md)
- [Logs viewer](../../../../../../../../../../../admin/logs/README.md) — tail these log files from a browser
- [v3 API](../../v3/README.md) — per-step call tracing beyond what log files can show
