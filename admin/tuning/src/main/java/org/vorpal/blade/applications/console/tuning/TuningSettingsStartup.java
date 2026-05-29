package org.vorpal.blade.applications.console.tuning;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Tuning admin app's SettingsManager at startup so its
/// `name` / `tagline` / `description` metadata appear in JMX for the
/// Admin Portal deck and the Configurator form editor. See memory `[[admin-apps-use-settingsmanager]]`.
@WebListener
public class TuningSettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(TuningSettingsStartup.class.getName());

	private SettingsManager<TuningSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, TuningSettings.class, new TuningSettingsSample());
			logger.info("tuning SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "tuning SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "tuning SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
