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
/// This is the headless, standalone alternative to the Configurator's
/// `autoPublish` setting, for sites that don't deploy the Configurator
/// UI — see the module README.
@WebListener
public class WatcherStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(WatcherStartup.class.getName());

	private SettingsManager<WatcherSettings> settingsManager;
	private ConfigurationMonitor monitor;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		// One startup line so operators know what's publishing config changes.
		// The double-publish note matters: the Configurator's autoPublish
		// setting watches the same directory.
		logger.info("watcher started: headless auto-publish of ./config/custom/vorpal/*.json "
				+ "edits via JMX. If the Configurator is also deployed with autoPublish "
				+ "enabled, edits publish twice — disable one of the two.");

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
