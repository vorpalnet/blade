package org.vorpal.blade.applications.watcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Starts the [ConfigurationMonitor] watcher thread when the watcher
/// WAR is deployed to the AdminServer, and stops it on undeploy.
///
/// The `enabled` flag in [WatcherSettings] is read once at startup. If
/// `false`, the WAR stays deployed but the watch thread is never
/// started. Flipping the flag requires the AdminServer to be restarted
/// (or the WAR redeployed).
///
/// This module exists as a transitional compatibility shim — see the
/// module README for the migration story.
@WebListener
public class WatcherStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(WatcherStartup.class.getName());

	private SettingsManager<WatcherSettings> settingsManager;
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
			settingsManager = new SettingsManager<>(sce, WatcherSettings.class, new WatcherSettingsSample());
		} catch (Exception e) {
			logger.log(Level.SEVERE, "watcher failed to load configuration; falling back to enabled=true", e);
		}

		boolean enabled = (settingsManager == null) || settingsManager.getCurrent().isEnabled();
		if (!enabled) {
			logger.warning("watcher is disabled via configuration (enabled=false). "
					+ "WAR deployed but inert. Set enabled=true and redeploy to resume auto-publish.");
			return;
		}

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
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				// best effort
			} finally {
				settingsManager = null;
			}
		}
	}
}
