package org.vorpal.blade.framework.v3.source;

import java.lang.management.ManagementFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers a [Source] MBean for every deployed BLADE app — automatically.
///
/// This listener lives inside the framework JAR, and the Servlet 3.0 container
/// scans `WEB-INF/lib` JARs for `@WebListener`, so any WAR that bundles the
/// framework gets its source inventory published with zero app code and zero
/// changes to the frozen v2 lifecycle classes. The ObjectName mirrors the
/// Configuration MBean's — `vorpal.blade:Name=<app>,Type=Source[,Cluster=...]`
/// with the same [SettingsManager#deriveName] name key — so the Callflow
/// Viewer joins the two by `Name`.
///
/// Registration is best-effort: a failure logs a warning and the app deploys
/// normally without a source inventory.
@WebListener
public class SourceRegistrar implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(SourceRegistrar.class.getName());

	private ObjectName objectName;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			ServletContext ctx = sce.getServletContext();
			String name = SettingsManager.flatten(SettingsManager.basename(SettingsManager.deriveName(ctx)));
			MBeanServer server = ManagementFactory.getPlatformMBeanServer();

			String cluster = clusterName(server);
			ObjectName on = new ObjectName(cluster != null
					? "vorpal.blade:Name=" + name + ",Type=Source,Cluster=" + cluster
					: "vorpal.blade:Name=" + name + ",Type=Source");

			StandardMBean mxbean = new StandardMBean(new Source(ctx, name), SourceMXBean.class, true);
			objectName = server.registerMBean(mxbean, on).getObjectName();
		} catch (Exception e) {
			logger.log(Level.WARNING, "Source MBean registration failed; app deploys without a source inventory", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (objectName != null) {
			try {
				ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
			} catch (Exception e) {
				logger.log(Level.FINE, "Source MBean unregister failed", e);
			} finally {
				objectName = null;
			}
		}
	}

	/// The cluster this node belongs to, or null on a non-clustered server
	/// (AdminServer). Same discovery the v2 SettingsManager does for the
	/// Configuration MBean — the two ObjectNames must agree on the Cluster key.
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
