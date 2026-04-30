package org.vorpal.blade.applications.console.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Optionally starts a [ConfigurationMonitor] thread that auto-publishes
/// on-disk `./config/custom/vorpal/*.json` edits via JMX — the same
/// behavior the legacy `watcher` WAR provides.
///
/// Gated on [ConfiguratorSettings#isAutoPublish()]. When `false`,
/// operators must Save + Publish explicitly through the Configurator
/// UI. Read once at startup; flipping the flag requires the AdminServer
/// to be restarted (or the WAR redeployed).
@WebListener
public class ConfigurationMonitorStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(ConfigurationMonitorStartup.class.getName());

	private SettingsManager<ConfiguratorSettings> settingsManager;
	private ConfigurationMonitor monitor;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, ConfiguratorSettings.class, new ConfiguratorSettingsSample());
		} catch (Exception e) {
			logger.log(Level.SEVERE, "configurator failed to load configuration; falling back to autoPublish=true", e);
		}

		boolean autoPublish = (settingsManager == null) || settingsManager.getCurrent().isAutoPublish();
		if (!autoPublish) {
			logger.info("configurator auto-publish is disabled. "
					+ "Use the Save + Publish flow in the UI to apply config changes.");
			return;
		}

		try {
			Path dir = Paths.get("./config/custom/vorpal/");
			monitor = new ConfigurationMonitor();
			monitor.initialize(dir, true);
			monitor.setDaemon(true);
			monitor.setName("BLADE-configurator-autopublish");
			monitor.start();
			logger.info("configurator auto-publish started, watching " + dir.toAbsolutePath());
		} catch (Exception e) {
			logger.log(Level.SEVERE, "configurator auto-publish failed to start", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (monitor != null) {
			try {
				monitor.shutdown();
				logger.info("configurator auto-publish stopped");
			} catch (Exception e) {
				logger.log(Level.WARNING, "configurator auto-publish shutdown error", e);
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
