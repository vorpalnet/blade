package org.vorpal.blade.framework.v3.net;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/// Registers this application's [Remotes] as the MBean
/// `vorpal.blade:Type=Remotes,Name=<application>` when it starts, and
/// unregisters it when it stops, so a redeploy never leaves the old copy's
/// classloader pinned by a bean nobody removed. Declared in the framework
/// JAR's `META-INF/web-fragment.xml`, so every BLADE WAR has it. The domain
/// runtime federates it with a `Location` key per server.
///
/// When the server runs the BLADE agent (`-javaagent:blade-agent.jar`), this
/// also registers the application's classloader with it, so the connections
/// and HTTP calls the application's own code makes, with whatever library,
/// are credited to it and served by the same MBean.
public class RemotesControl implements ServletContextListener, RemotesMXBean {

	private ObjectName name;

	/// The BLADE agent's registry, on the boot class path when the server was
	/// started with `-javaagent:blade-agent.jar`; absent otherwise.
	static final String AGENT = "org.vorpal.blade.framework.v3.net.agent.boot.NetEvents";

	private ClassLoader loader;

	/// What the framework recorded ([Remotes]), followed by what the BLADE
	/// agent recorded of this application's own code, when the server runs
	/// it: one array, the agent's entries marked `"agent":true`.
	@Override
	public String getRemotesJson() {
		String framework = Remotes.json();
		String agent = (String) callAgent("json", new Class<?>[] { ClassLoader.class }, loader);
		if (agent == null || agent.length() <= 2) {
			return framework;
		}
		return framework.length() <= 2 ? agent
				: framework.substring(0, framework.length() - 1) + "," + agent.substring(1);
	}

	/// Calls the agent's registry, if it is there. Never throws.
	private static Object callAgent(String method, Class<?>[] types, Object... args) {
		try {
			Class<?> agent = Class.forName(AGENT, false, null);
			return agent.getMethod(method, types).invoke(null, args);
		} catch (ClassNotFoundException noAgent) {
			return null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			String app = sce.getServletContext().getContextPath().replaceFirst("^/", "");
			name = new ObjectName("vorpal.blade:Type=Remotes,Name=" + ObjectName.quote(app.isEmpty() ? "root" : app));
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();
			if (server.isRegistered(name)) {
				server.unregisterMBean(name);
			}
			server.registerMBean(this, name);
		} catch (Exception e) {
			name = null;
		}
		// Calls this application's own code makes are credited to it by the
		// agent through its classloader.
		loader = sce.getServletContext().getClassLoader();
		callAgent("register", new Class<?>[] { ClassLoader.class, String.class }, loader,
				sce.getServletContext().getContextPath().replaceFirst("^/", ""));
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		callAgent("unregister", new Class<?>[] { ClassLoader.class }, loader);
		loader = null;
		try {
			if (name != null) {
				ManagementFactory.getPlatformMBeanServer().unregisterMBean(name);
			}
		} catch (Exception e) {
			// already gone
		}
		name = null;
	}
}
