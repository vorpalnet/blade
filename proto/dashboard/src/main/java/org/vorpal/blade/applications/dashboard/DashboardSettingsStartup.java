package org.vorpal.blade.applications.dashboard;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the dashboard's SettingsManager so it appears on the Admin Portal
/// deck and its configuration is Configurator-editable. The live settings are
/// stashed on the ServletContext so the data servlet reads current values
/// without a redeploy.
@WebListener
public class DashboardSettingsStartup implements ServletContextListener {

	public static final String SETTINGS_ATTR = "org.vorpal.blade.dashboard.settings";

	private static final Logger logger = Logger.getLogger(DashboardSettingsStartup.class.getName());

	private SettingsManager<DashboardSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, DashboardSettings.class, new DashboardSettingsSample());
			sce.getServletContext().setAttribute(SETTINGS_ATTR, settingsManager);
			logger.info("dashboard settings registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "dashboard settings failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		sce.getServletContext().removeAttribute(SETTINGS_ATTR);
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "dashboard settings unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
