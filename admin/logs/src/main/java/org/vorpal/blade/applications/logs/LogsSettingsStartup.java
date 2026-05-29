package org.vorpal.blade.applications.logs;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.vorpal.blade.framework.v2.config.SettingsManager;

/// Registers the Logs admin app's SettingsManager at startup so its
/// `name` / `tagline` / `description` metadata appear in JMX for the
/// Admin Portal deck and the Configurator form editor. See memory
/// `[[admin-apps-use-settingsmanager]]`.
///
/// Separate from `LogReaderListener` (which is a per-WAR shim for the
/// framework's LogReaderRegistrar MBean) — different concern, different
/// MBean, different lifecycle.
@WebListener
public class LogsSettingsStartup implements ServletContextListener {

	private static final Logger logger = Logger.getLogger(LogsSettingsStartup.class.getName());

	private SettingsManager<LogsSettings> settingsManager;

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			settingsManager = new SettingsManager<>(sce, LogsSettings.class, new LogsSettingsSample());
			logger.info("logs SettingsManager registered");
		} catch (Exception e) {
			logger.log(Level.SEVERE, "logs SettingsManager failed to register", e);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		if (settingsManager != null) {
			try {
				settingsManager.unregister();
			} catch (Exception e) {
				logger.log(Level.WARNING, "logs SettingsManager unregister error", e);
			} finally {
				settingsManager = null;
			}
		}
	}
}
