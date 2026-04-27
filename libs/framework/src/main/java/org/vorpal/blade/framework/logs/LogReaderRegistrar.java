package org.vorpal.blade.framework.logs;

import java.lang.management.ManagementFactory;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/// Registers a [VorpalLogReader] on the platform MBeanServer the first time
/// any BLADE WAR in this JVM starts. Idempotent: subsequent WARs hit
/// `InstanceAlreadyExistsException` and quietly skip.
///
/// Wired up via `META-INF/web-fragment.xml` in the framework JAR so every WAR
/// that uses the shared library picks it up — no per-service web.xml edit.
@WebListener
public class LogReaderRegistrar implements ServletContextListener {

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			ensureRegistered();
		} catch (Exception e) {
			sce.getServletContext().log("LogReaderRegistrar: failed to register MBean", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		// Leave the MBean registered — other WARs in this JVM may still need it.
		// WebLogic tears down the platform MBeanServer at JVM shutdown.
	}

	/// Idempotent. First caller per JVM wins; subsequent callers see the
	/// MBean already registered and return. Safe to call from any number of
	/// listeners (per-WAR shims, etc.) on the same node.
	public static void ensureRegistered() throws Exception {
		String serverName = System.getProperty("weblogic.Name");
		if (serverName == null || serverName.isEmpty()) {
			serverName = "standalone";
		}
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		ObjectName on = new ObjectName("vorpal.blade:Type=LogReader,Name=" + serverName);
		if (mbs.isRegistered(on)) {
			return;
		}
		VorpalLogReader reader = new VorpalLogReader(serverName, VorpalLogReader.defaultLogsRoot(serverName));
		try {
			mbs.registerMBean(reader, on);
		} catch (InstanceAlreadyExistsException ignored) {
			// Race with a sibling WAR — fine.
		}
	}
}
