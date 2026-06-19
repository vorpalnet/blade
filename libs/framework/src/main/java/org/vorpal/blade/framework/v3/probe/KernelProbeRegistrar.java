package org.vorpal.blade.framework.v3.probe;

import java.lang.management.ManagementFactory;
import java.nio.file.Paths;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/// Registers a [KernelProbe] on the platform MBeanServer the first time any BLADE
/// WAR in this JVM starts — so every node exposes `vorpal.blade:Type=KernelProbe,
/// Name=<server>` for the Tuning app to read over DomainRuntime. Idempotent;
/// mirrors `LogReaderRegistrar`. Wired via `META-INF/web-fragment.xml` in the
/// framework JAR so every shared-library WAR picks it up — no per-service edit.
@WebListener
public class KernelProbeRegistrar implements ServletContextListener {

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			ensureRegistered();
		} catch (Exception e) {
			sce.getServletContext().log("KernelProbeRegistrar: failed to register MBean", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		// Leave the MBean registered — other WARs in this JVM may still need it.
	}

	/// Idempotent. First caller per JVM wins; subsequent callers skip.
	public static void ensureRegistered() throws Exception {
		String serverName = System.getProperty("weblogic.Name");
		if (serverName == null || serverName.isEmpty()) {
			serverName = "standalone";
		}
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		ObjectName on = new ObjectName("vorpal.blade:Type=KernelProbe,Name=" + serverName);
		if (mbs.isRegistered(on)) {
			return;
		}
		try {
			mbs.registerMBean(new KernelProbe(serverName, Paths.get("/")), on);
		} catch (InstanceAlreadyExistsException ignored) {
			// Race with a sibling WAR — fine.
		}
	}
}
