package org.vorpal.blade.applications.logs;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.vorpal.blade.framework.logs.LogReaderRegistrar;

/// Thin shim that lives in this WAR's own `WEB-INF/classes` so the WLS
/// deployment-activation pass can resolve the listener class without needing
/// the shared library's classloader (which is merged in *after* validation).
/// The body calls `LogReaderRegistrar.ensureRegistered()` lazily at request
/// time, by which point the framework class is reachable.
public class LogReaderListener implements ServletContextListener {

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			LogReaderRegistrar.ensureRegistered();
		} catch (Throwable t) {
			sce.getServletContext().log("LogReaderListener: failed to register MBean", t);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
	}
}
