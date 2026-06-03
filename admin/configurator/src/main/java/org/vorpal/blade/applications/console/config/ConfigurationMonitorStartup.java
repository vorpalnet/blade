package org.vorpal.blade.applications.console.config;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/// Registers the Configurator's [ConfiguratorSettingsManager] at startup and
/// tears it down on undeploy.
///
/// The auto-publish [ConfigurationMonitor] is owned entirely by the manager's
/// [ConfiguratorSettingsManager#initialize] hook, which the framework invokes
/// during construction and again on every config reload. That is what makes
/// the [ConfiguratorSettings#isAutoPublish] toggle take effect live — flipping
/// it in the UI republishes `configurator.json`, which reloads this manager,
/// which starts or stops the monitor accordingly.
@WebListener
public class ConfigurationMonitorStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(ConfigurationMonitorStartup.class.getName());

	private ConfiguratorSettingsManager settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new ConfiguratorSettingsManager(sce);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "configurator settings manager failed to initialize", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.shutdown();
			} catch (Exception e) {
				logger.log(Level.WARNING, "configurator auto-publish shutdown error", e);
			}
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
