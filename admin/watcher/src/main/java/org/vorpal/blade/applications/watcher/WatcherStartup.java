package org.vorpal.blade.applications.watcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Starts the {@link ConfigurationMonitor} watcher thread when the
 * watcher WAR is deployed to the AdminServer, and stops it on
 * undeploy.
 *
 * <p>This is the only entry point in this WAR. There are no servlets,
 * no JSPs, no UI. The watcher runs as a daemon thread for the lifetime
 * of the deployment, watching {@code ./config/custom/vorpal/} for
 * {@code *.json} changes and calling {@code SettingsMXBean.reload()}
 * via JMX whenever one is detected.
 *
 * <p>This module exists as a transitional compatibility shim — see the
 * module README for the migration story.
 */
@WebListener
public class WatcherStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(WatcherStartup.class.getName());

	private ConfigurationMonitor monitor;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		// Loud, persistent migration pressure on every startup. Operators
		// who deploy this WAR see this in the AdminServer log every time
		// the AdminServer boots.
		logger.warning("watcher is a legacy compatibility shim that "
				+ "auto-publishes config file changes outside the Configurator. "
				+ "Migrate to the Configurator (admin/configurator) when your "
				+ "operations scripts are ready. This module will be removed in "
				+ "a future BLADE release.");

		try {
			Path dir = Paths.get("./config/custom/vorpal/");
			monitor = new ConfigurationMonitor();
			monitor.initialize(dir, true);
			monitor.setDaemon(true);
			monitor.setName("BLADE-watcher");
			monitor.start();
			logger.info("watcher started, watching " + dir.toAbsolutePath());
		} catch (Exception e) {
			logger.log(Level.SEVERE, "watcher failed to start", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (monitor != null) {
			try {
				monitor.shutdown();
				logger.info("watcher stopped");
			} catch (Exception e) {
				logger.log(Level.WARNING, "watcher shutdown error", e);
			} finally {
				monitor = null;
			}
		}
	}
}
