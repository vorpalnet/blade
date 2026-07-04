package org.vorpal.blade.framework.v3.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers a [Trace] MBean for every deployed BLADE app — automatically,
/// exactly like `v3.source.SourceRegistrar`: a `@WebListener` inside the
/// framework JAR, scanned from `WEB-INF/lib` by the Servlet 3.0 container, so
/// no app code and no v2 changes. ObjectName
/// `vorpal.blade:Name=<app>,Type=Trace[,Cluster=...]` — same Name key as the
/// Configuration and Source MBeans, so the viewer joins all three.
///
/// Best-effort: a failure logs a warning and the app deploys without chain
/// tracing.
@WebListener
public class TraceRegistrar implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(TraceRegistrar.class.getName());

	private ObjectName objectName;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			String name = SettingsManager
					.flatten(SettingsManager.basename(SettingsManager.deriveName(sce.getServletContext())));
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();

			String cluster = clusterName(server);
			ObjectName on = new ObjectName(cluster != null
					? "vorpal.blade:Name=" + name + ",Type=Trace,Cluster=" + cluster
					: "vorpal.blade:Name=" + name + ",Type=Trace");

			StandardMBean mxbean = new StandardMBean(new Trace(name), TraceMXBean.class, true);
			objectName = server.registerMBean(mxbean, on).getObjectName();
		} catch (Exception e) {
			logger.log(Level.WARNING, "Trace MBean registration failed; app deploys without chain tracing", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (objectName != null) {
			try {
				ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
			} catch (Exception e) {
				logger.log(Level.FINE, "Trace MBean unregister failed", e);
			} finally {
				objectName = null;
			}
		}
	}

	/// Same cluster discovery as SourceRegistrar — the ObjectNames must agree
	/// on the Cluster key.
	private static String clusterName(MBeanServer server) {
		try {
			String serverName = System.getProperty("weblogic.Name");
			ObjectName managedServer = new ObjectName("com.bea:Name=" + serverName + ",Type=Server");
			ObjectName cluster = (ObjectName) server.getAttribute(managedServer, "Cluster");
			return cluster != null ? (String) server.getAttribute(cluster, "Name") : null;
		} catch (Exception e) {
			return null;
		}
	}
}
