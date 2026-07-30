package org.vorpal.blade.applications.events;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the console's SettingsManager so it appears on the Admin Portal
/// deck and its configuration is Configurator-editable.
@WebListener
public class EventsAdminSettingsStartup implements ServletContextListener {

	/// ServletContext attribute holding the live settings, so the REST layer
	/// reads current values without a redeploy.
	public static final String SETTINGS_ATTR = "org.vorpal.blade.events.admin.settings";

	private static final Logger logger = Logger.getLogger(EventsAdminSettingsStartup.class.getName());

	private SettingsManager<EventsAdminSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, EventsAdminSettings.class, new EventsAdminSettingsSample());
			sce.getServletContext().setAttribute(SETTINGS_ATTR, settingsManager);
			logger.info("events console settings registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "events console settings failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		sce.getServletContext().removeAttribute(SETTINGS_ATTR);
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "events console settings unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
