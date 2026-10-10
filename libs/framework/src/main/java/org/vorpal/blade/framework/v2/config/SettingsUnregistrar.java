package org.vorpal.blade.framework.v2.config;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/// Unregisters, when an application stops, every [SettingsManager] it
/// registered, so no application has to remember to call
/// [SettingsManager#unregister()] itself.
///
/// A registered MBean is held by the server's MBean server for the life of
/// the JVM, and through it the application's whole classloader. An
/// application that forgot to unregister kept its first deployed copy in
/// memory until the server restarted. Each [SettingsManager] built with a
/// servlet context records itself on that context; this listener, declared in
/// the framework JAR's `META-INF/web-fragment.xml` and therefore present in
/// every BLADE WAR, unregisters them all at `contextDestroyed`.
///
/// An application that still calls `unregister()` itself is unaffected:
/// `unregister()` does nothing for a bean that is already gone.
public class SettingsUnregistrar implements ServletContextListener {

	private static final String ATTRIBUTE = SettingsUnregistrar.class.getName();

	/// Records a manager so it is unregistered when `context` is destroyed.
	static void track(ServletContext context, SettingsManager<?> manager) {
		managers(context).add(manager);
	}

	@SuppressWarnings("unchecked")
	private static Set<SettingsManager<?>> managers(ServletContext context) {
		synchronized (context) {
			Set<SettingsManager<?>> set = (Set<SettingsManager<?>>) context.getAttribute(ATTRIBUTE);
			if (set == null) {
				set = ConcurrentHashMap.newKeySet();
				context.setAttribute(ATTRIBUTE, set);
			}
			return set;
		}
	}

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		// Nothing to do until a SettingsManager registers.
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		ServletContext context = sce.getServletContext();
		for (SettingsManager<?> manager : managers(context)) {
			try {
				manager.unregister();
			} catch (Exception e) {
				context.log("SettingsUnregistrar: " + manager.getServletContextName() + " not unregistered", e);
			}
		}
		context.removeAttribute(ATTRIBUTE);
	}
}
