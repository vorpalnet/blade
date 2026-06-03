package org.vorpal.blade.applications.analytics;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Analytics admin app's SettingsManager at startup so its
/// `name` / `tagline` / `description` metadata appear in JMX for the
/// Admin Portal launcher and the Configurator form editor. See memory
/// `[[admin-apps-use-settingsmanager]]`.
@WebListener
public class AnalyticsAdminSettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(AnalyticsAdminSettingsStartup.class.getName());

	private SettingsManager<AnalyticsAdminSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, AnalyticsAdminSettings.class,
					new AnalyticsAdminSettingsSample());
			logger.info("analytics-admin SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "analytics-admin SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "analytics-admin SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
