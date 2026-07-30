package org.vorpal.blade.applications.metrics;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the console's SettingsManager, which is what puts it on the portal
/// deck and makes its configuration Configurator-editable.
@WebListener
public class MetricsAdminSettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(MetricsAdminSettingsStartup.class.getName());

	private SettingsManager<MetricsAdminSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, MetricsAdminSettings.class,
					new MetricsAdminSettingsSample());
			logger.info("metrics console settings registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "metrics console settings failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "metrics console settings unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
